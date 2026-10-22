package co.yixiang.yshop.module.store.service.printer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;

/** Durable event expansion and print delivery. Provider I/O is always outside a database transaction. */
@Service
public class OrderPrintJobWorker {
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_LIMIT = 100;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PrinterService printers;
    private final PrinterProviderRegistry providers;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPrintJobWorker(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            PrinterService printers, PrinterProviderRegistry providers) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.printers = printers;
        this.providers = providers;
    }

    @Scheduled(fixedDelayString = "${yshop.printer.order-print-poll-ms:5000}")
    public void poll() {
        recoverExpiredJobs();
        processPendingEvents();
        dispatchPendingJobs();
    }

    /** Replays committed outbox rows. Unique event/device/purpose keys make concurrent replay safe. */
    public int processPendingEvents() {
        return tx.execute(status -> {
            List<Long> ids = jdbc.query("SELECT id FROM yshop_order_fulfillment_print_event "
                    + "WHERE state IN ('PENDING','WAITING_DEVICE') AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP) "
                    + "ORDER BY id LIMIT 100 FOR UPDATE", (rs, row) -> rs.getLong(1));
            for (Long id : ids) expandEvent(id);
            return ids.size();
        });
    }

    private void expandEvent(long id) {
        Event event = jdbc.queryForObject("SELECT id,tenant_id,order_id,shop_id,event_key,payload_snapshot "
                + "FROM yshop_order_fulfillment_print_event WHERE id=? FOR UPDATE", this::mapEvent, id);
        List<DeviceTarget> targets = jdbc.query("SELECT p.id,p.name,p.provider_code,p.device_code,p.enabled,p.deleted,"
                + "p.authorization,p.device_state FROM yshop_printer_binding b JOIN yshop_printer p "
                + "ON p.id=b.printer_id AND p.tenant_id=b.tenant_id WHERE b.tenant_id=? AND b.shop_id=? ORDER BY p.id",
                this::mapTarget, event.tenantId(), event.shopId());
        boolean waiting = targets.isEmpty();
        String reason = targets.isEmpty() ? "NO_BOUND_DEVICE" : null;
        for (DeviceTarget target : targets) {
            String notReady = notReady(target);
            if (notReady != null) {
                waiting = true;
                reason = notReady;
                continue;
            }
            String requestKey = "order-" + event.id() + "-" + target.id();
            jdbc.update("INSERT INTO yshop_order_print_job(tenant_id,event_id,event_key,order_id,shop_id,printer_id,"
                    + "printer_name_snapshot,provider_code_snapshot,device_code_snapshot,request_key,request_hash,purpose,state,purpose_key,"
                    + "payload_snapshot,attempts,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,'ORDER','PENDING','ORDER',?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                    + "ON DUPLICATE KEY UPDATE request_key=request_key",
                    event.tenantId(), event.id(), event.eventKey(), event.orderId(), event.shopId(), target.id(), target.name(),
                    target.providerCode(), target.deviceCode(), requestKey, hash(event.eventKey() + "|" + target.id() + "|ORDER"),
                    event.payloadSnapshot());
        }
        jdbc.update("UPDATE yshop_order_fulfillment_print_event SET state=?,attempts=attempts+1,"
                + "next_attempt_at=CASE WHEN ? THEN TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP) ELSE NULL END,last_error=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
                waiting ? "WAITING_DEVICE" : "PROCESSED", waiting, reason, id);
    }

    private String notReady(DeviceTarget device) {
        if (device.deleted() || !device.enabled()) return "PRINTER_DISABLED";
        PrinterProvider provider;
        try {
            provider = providers.require(device.providerCode());
        } catch (PrinterFailure failure) {
            return failure.getMessage();
        }
        if (provider.info().requiresAuthorization() && !"AUTHORIZED".equals(device.authorization()))
            return "PRINTER_NOT_AUTHORIZED";
        if (!"ONLINE".equals(device.deviceState())) return "DEVICE_" + device.deviceState();
        if (!provider.info().capabilities().textPrint()) return "UNSUPPORTED_OPERATION";
        return null;
    }

    private Event mapEvent(ResultSet rs, int row) throws SQLException {
        return new Event(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("order_id"), rs.getLong("shop_id"),
                rs.getString("event_key"), rs.getString("payload_snapshot"));
    }

    private DeviceTarget mapTarget(ResultSet rs, int row) throws SQLException {
        return new DeviceTarget(rs.getLong("id"), rs.getString("name"), rs.getString("provider_code"),
                rs.getString("device_code"), rs.getBoolean("enabled"), rs.getBoolean("deleted"),
                rs.getString("authorization"), rs.getString("device_state"));
    }

    /** Any task whose lease expired may have reached the printer. It becomes UNKNOWN and is never reclaimed. */
    public int recoverExpiredJobs() {
        return jdbc.update("UPDATE yshop_order_print_job SET state='UNKNOWN',last_error='WORKER_LEASE_EXPIRED',"
                + "lease_expires_at=NULL,update_time=CURRENT_TIMESTAMP WHERE state='SENDING' AND lease_expires_at<=CURRENT_TIMESTAMP");
    }

    /** Explicit refresh only reads/repairs local state and never prepares a provider session. */
    public void refreshJob(long tenantId,long jobId) {
        jdbc.update("UPDATE yshop_order_print_job SET state='UNKNOWN',last_error='WORKER_LEASE_EXPIRED',"
                + "lease_expires_at=NULL,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND state='SENDING' "
                + "AND lease_expires_at<=CURRENT_TIMESTAMP",tenantId,jobId);
    }

    /** Explicit event refresh rechecks local bindings for one tenant-scoped outbox event only. */
    public void refreshEvent(long tenantId,long eventId) {
        tx.executeWithoutResult(status -> {
            List<Long> ids=jdbc.query("SELECT id FROM yshop_order_fulfillment_print_event WHERE tenant_id=? AND id=? "
                    + "AND state IN ('PENDING','WAITING_DEVICE') FOR UPDATE",(rs,row)->rs.getLong(1),tenantId,eventId);
            if(ids.isEmpty()) throw new PrinterFailure("PRINT_JOB_NOT_FOUND",false);
            expandEvent(eventId);
        });
    }

    /** Sends committed PENDING jobs once; ambiguous responses and expired leases are never auto-retried. */
    public int dispatchPendingJobs() {
        int sent = 0;
        for (int count = 0; count < BATCH_LIMIT; count++) {
            Claim claim = claim();
            if (claim == null) break;
            PrinterService.OrderPrintAttempt attempt = printers.sendOrderPrint(claim.tenantId(), claim.printerId(),
                    claim.shopId(), claim.id(), claim.leaseToken(), claim.requestKey(), receipt(claim.payloadSnapshot()));
            finish(claim, attempt);
            if (attempt.submitted()) sent++;
        }
        return sent;
    }

    private Claim claim() {
        return tx.execute(status -> {
            List<Long> ids = jdbc.query("SELECT id FROM yshop_order_print_job WHERE state='PENDING' "
                    + "AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP) ORDER BY id LIMIT 1 FOR UPDATE",
                    (rs, row) -> rs.getLong(1));
            if (ids.isEmpty()) return null;
            long id = ids.get(0);
            String token = UUID.randomUUID().toString();
            int changed = jdbc.update("UPDATE yshop_order_print_job SET state='SENDING',attempts=attempts+1,lease_token=?,"
                    + "lease_expires_at=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP),next_attempt_at=NULL,last_error=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND state='PENDING'",
                    token, id);
            if (changed != 1) return null;
            return jdbc.queryForObject("SELECT id,tenant_id,shop_id,printer_id,request_key,payload_snapshot,attempts,lease_token "
                    + "FROM yshop_order_print_job WHERE id=?", (rs, row) -> new Claim(rs.getLong("id"), rs.getLong("tenant_id"),
                    rs.getLong("shop_id"), rs.getLong("printer_id"), rs.getString("request_key"),
                    rs.getString("payload_snapshot"), rs.getInt("attempts"), rs.getString("lease_token")), id);
        });
    }

    private void finish(Claim claim, PrinterService.OrderPrintAttempt attempt) {
        if (attempt.result() != null) {
            jdbc.update("UPDATE yshop_order_print_job SET state='ACCEPTED',provider_job_id=?,last_error=NULL,"
                    + "lease_expires_at=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND state IN ('SENDING','UNKNOWN')",
                    attempt.result().providerJobId(), claim.id(), claim.leaseToken());
            return;
        }
        PrinterFailure failure = attempt.failure();
        String error = failure == null ? "PRINT_FAILED" : failure.getMessage();
        if (attempt.submitted()) {
            String state = failure != null && failure.uncertain() ? "UNKNOWN" : "FAILED";
            jdbc.update("UPDATE yshop_order_print_job SET state=?,last_error=?,lease_expires_at=NULL,update_time=CURRENT_TIMESTAMP "
                    + "WHERE id=? AND lease_token=? AND state IN ('SENDING','UNKNOWN')", state, error, claim.id(), claim.leaseToken());
            return;
        }
        if (failure != null && ("PRINTER_NOT_BOUND".equals(error) || "WORKER_LEASE_EXPIRED".equals(error))) {
            jdbc.update("UPDATE yshop_order_print_job SET state='FAILED',last_error=?,lease_expires_at=NULL,next_attempt_at=NULL,"
                    + "update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND state='SENDING'", error, claim.id(), claim.leaseToken());
            return;
        }
        if (claim.attempts() < MAX_ATTEMPTS) {
            long delay = Math.min(900L, 15L << Math.min(claim.attempts(), 6));
            jdbc.update("UPDATE yshop_order_print_job SET state='PENDING',last_error=?,lease_expires_at=NULL,"
                    + "next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),update_time=CURRENT_TIMESTAMP "
                    + "WHERE id=? AND lease_token=? AND state='SENDING'", error, delay, claim.id(), claim.leaseToken());
        } else {
            jdbc.update("UPDATE yshop_order_print_job SET state='FAILED',last_error=?,lease_expires_at=NULL,"
                    + "update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND state='SENDING'", error, claim.id(), claim.leaseToken());
        }
    }

    private Receipt receipt(String snapshot) {
        try {
            JsonNode payload = json.readTree(snapshot);
            List<String> lines = new ArrayList<>();
            lines.add("自提订单 " + payload.path("orderCode").asText());
            lines.add("门店：" + payload.path("shopName").asText());
            lines.add("取餐号：" + payload.path("pickupNumber").asText());
            lines.add("联系电话：" + payload.path("contactPhone").asText());
            lines.add("下单时间：" + payload.path("orderCreatedAt").asText());
            for (JsonNode item : payload.path("items")) {
                lines.add(item.path("title").asText() + " x" + item.path("quantity").asText());
                String spec = item.path("specification").asText();
                if (!spec.isBlank()) lines.add("规格：" + spec);
                appendCustomizations(item, lines);
            }
            lines.add("商品数：" + payload.path("totalQuantity").asText());
            lines.add("实付：￥" + payload.path("paidAmount").asText());
            return new Receipt("自提订单小票", lines, 1);
        } catch (Exception failure) {
            throw new PrinterFailure("PRINT_SNAPSHOT_INVALID", false);
        }
    }

    private void appendCustomizations(JsonNode item, List<String> lines) {
        JsonNode value = item.path("cartDetails");
        if (value.isMissingNode() || value.isNull()) return;
        try {
            JsonNode details = value.isTextual() ? json.readTree(value.asText()) : value;
            JsonNode options = details.path("options");
            if (!options.isArray()) return;
            for (JsonNode option : options) {
                String name = option.path("name").asText("").trim();
                if (name.isEmpty()) continue;
                int quantity = option.path("quantity").asInt(1);
                if (quantity < 1 || quantity > 100) continue;
                String group = option.path("groupName").asText("").trim();
                StringBuilder line = new StringBuilder("定制：");
                if (!group.isEmpty()) line.append(group).append(' ');
                line.append(name);
                if (quantity > 1) line.append(" x").append(quantity);
                JsonNode surchargeNode = option.path("surcharge");
                if (surchargeNode.isNumber()) {
                    java.math.BigDecimal surcharge = surchargeNode.decimalValue()
                            .multiply(java.math.BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.UNNECESSARY);
                    if (surcharge.signum() > 0) line.append("（+￥").append(surcharge.toPlainString()).append('）');
                }
                lines.add(line.toString());
            }
        } catch (Exception ignored) {
            // Legacy cart snapshots may not contain catalog option JSON.
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }

    public record JobView(Long id, Long eventId, Long orderId, Long shopId, Long printerId, String printerName,
            String state, String errorCode, String providerJobId, int attempts, Long parentJobId, String reprintReason,
            String payloadSnapshot, LocalDateTime createTime) {}

    private record Event(long id, long tenantId, long orderId, long shopId, String eventKey, String payloadSnapshot) {}
    private record DeviceTarget(long id, String name, String providerCode, String deviceCode, boolean enabled,
            boolean deleted, String authorization, String deviceState) {}
    private record Claim(long id, long tenantId, long shopId, long printerId, String requestKey,
            String payloadSnapshot, int attempts, String leaseToken) {}
}
