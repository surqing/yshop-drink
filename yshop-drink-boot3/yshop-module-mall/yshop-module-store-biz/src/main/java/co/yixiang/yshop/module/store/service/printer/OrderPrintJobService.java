package co.yixiang.yshop.module.store.service.printer;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static co.yixiang.yshop.module.store.service.printer.OrderPrintJobWorker.JobView;

/** Headquarters-only management surface for durable order print tasks. */
@Service
public class OrderPrintJobService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PrinterService printerAccess;
    private final OrderPrintJobWorker worker;

    public OrderPrintJobService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            PrinterService printerAccess, OrderPrintJobWorker worker) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.printerAccess = printerAccess;
        this.worker = worker;
    }

    public PageResult<JobView> page(int pageNo, int pageSize, Long shopId) {
        long tenant = printerAccess.requireHeadquartersTenant();
        String filter = shopId == null ? "" : " WHERE shop_id=?";
        String jobs = "SELECT id,event_id,order_id,shop_id,printer_id,printer_name_snapshot AS printer_name,state,last_error,provider_job_id,attempts,parent_job_id,reprint_reason,payload_snapshot,create_time FROM yshop_order_print_job WHERE tenant_id=?";
        String waiting = "SELECT -id AS id,id AS event_id,order_id,shop_id,NULL AS printer_id,NULL AS printer_name,state,last_error,NULL AS provider_job_id,attempts,NULL AS parent_job_id,NULL AS reprint_reason,payload_snapshot,create_time FROM yshop_order_fulfillment_print_event WHERE tenant_id=? AND state IN ('PENDING','WAITING_DEVICE')";
        String union = "(" + jobs + " UNION ALL " + waiting + ") print_rows";
        List<Object> countArgs = new ArrayList<>(); countArgs.add(tenant); countArgs.add(tenant);
        List<Object> rowsArgs = new ArrayList<>(); rowsArgs.add(tenant); rowsArgs.add(tenant);
        String outer = "SELECT * FROM " + union + filter;
        if (shopId != null) { countArgs.add(shopId); rowsArgs.add(shopId); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + union + filter, Long.class, countArgs.toArray());
        rowsArgs.add(pageSize); rowsArgs.add((pageNo - 1) * pageSize);
        List<JobView> rows = jdbc.query(outer + " ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?", this::map, rowsArgs.toArray());
        return new PageResult<>(rows, total);
    }

    public JobView get(long id) {
        long tenant = printerAccess.requireHeadquartersTenant();
        if (id < 0) return waitingEvent(tenant, -id);
        List<JobView> rows = jdbc.query("SELECT id,event_id,order_id,shop_id,printer_id,printer_name_snapshot AS printer_name,state,last_error,provider_job_id,attempts,parent_job_id,reprint_reason,payload_snapshot,create_time FROM yshop_order_print_job WHERE tenant_id=? AND id=?",
                this::map, tenant, id);
        if (rows.isEmpty()) throw new PrinterFailure("PRINT_JOB_NOT_FOUND", false);
        return rows.get(0);
    }

    public JobView refresh(long id) {
        long tenant = printerAccess.requireHeadquartersTenant();
        if (id < 0) worker.refreshEvent(tenant, -id);
        else worker.refreshJob(tenant, id);
        return get(id);
    }

    public JobView reprint(ReprintRequest request) {
        long parentId=request.jobId();
        long tenant = printerAccess.requireHeadquartersTenant();
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) throw new PrinterFailure("HEADQUARTERS_ONLY", false);
        String reason = maskPhones(request.reason().trim());
        String requestHash = hash(tenant + "|" + parentId + "|REPRINT|" + reason);
        return tx.execute(status -> {
            JobView prior = findByRequestKey(tenant, request.requestKey());
            if (prior != null) {
                String priorHash = jdbc.queryForObject("SELECT request_hash FROM yshop_order_print_job WHERE tenant_id=? AND id=?", String.class, tenant, prior.id());
                if (!requestHash.equals(priorHash)) throw new PrinterFailure("REQUEST_CONFLICT", false);
                return prior;
            }
            Parent parent = jdbc.query("SELECT id,event_id,event_key,order_id,shop_id,printer_id,printer_name_snapshot,provider_code_snapshot,device_code_snapshot,state,payload_snapshot FROM yshop_order_print_job WHERE tenant_id=? AND id=? FOR UPDATE",
                    (rs, row) -> new Parent(rs.getLong("id"), rs.getLong("event_id"), rs.getString("event_key"), rs.getLong("order_id"),
                            rs.getLong("shop_id"), rs.getLong("printer_id"), rs.getString("printer_name_snapshot"),
                            rs.getString("provider_code_snapshot"), rs.getString("device_code_snapshot"), rs.getString("state"),
                            rs.getString("payload_snapshot")), tenant, parentId).stream().findFirst().orElse(null);
            if (parent == null) throw new PrinterFailure("PRINT_JOB_NOT_FOUND", false);
            if (!List.of("ACCEPTED", "FAILED", "UNKNOWN").contains(parent.state())) throw new PrinterFailure("JOB_NOT_REPRINTABLE", false);
            int bound = jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer p JOIN yshop_printer_binding b "
                    + "ON b.tenant_id=p.tenant_id AND b.printer_id=p.id WHERE p.tenant_id=? AND p.id=? AND p.deleted=0 AND b.shop_id=?",
                    Integer.class, tenant, parent.printerId(), parent.shopId());
            if (bound != 1) throw new PrinterFailure("PRINTER_NOT_BOUND", false);
            jdbc.update("INSERT INTO yshop_order_print_job(tenant_id,event_id,event_key,order_id,shop_id,printer_id,"
                    + "printer_name_snapshot,provider_code_snapshot,device_code_snapshot,request_key,request_hash,purpose,state,purpose_key,payload_snapshot,"
                    + "attempts,parent_job_id,reprint_reason,created_by,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,'REPRINT','PENDING',NULL,?,0,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                    + "ON DUPLICATE KEY UPDATE request_key=request_key",
                    tenant,parent.eventId(),parent.eventKey(),parent.orderId(),parent.shopId(),parent.printerId(),parent.printerName(),
                    parent.providerCode(),parent.deviceCode(),request.requestKey(),requestHash,parent.payload(),parent.id(),reason,actor);
            // A concurrent idempotent insert can win after this transaction's first
            // consistent read. Use a current read so MySQL REPEATABLE READ can see it.
            JobView created=findByRequestKeyForUpdate(tenant,request.requestKey());
            if(created==null) throw new PrinterFailure("PRINT_JOB_NOT_FOUND",false);
            String createdHash=jdbc.queryForObject("SELECT request_hash FROM yshop_order_print_job WHERE tenant_id=? AND id=?",String.class,tenant,created.id());
            if(!requestHash.equals(createdHash)) throw new PrinterFailure("REQUEST_CONFLICT",false);
            return created;
        });
    }

    private JobView waitingEvent(long tenant, long eventId) {
        List<JobView> rows = jdbc.query("SELECT -id AS id,id AS event_id,order_id,shop_id,NULL AS printer_id,NULL AS printer_name,state,last_error,NULL AS provider_job_id,attempts,NULL AS parent_job_id,NULL AS reprint_reason,payload_snapshot,create_time FROM yshop_order_fulfillment_print_event WHERE tenant_id=? AND id=? AND state IN ('PENDING','WAITING_DEVICE')",
                this::map, tenant, eventId);
        if (rows.isEmpty()) throw new PrinterFailure("PRINT_JOB_NOT_FOUND", false);
        return rows.get(0);
    }

    private JobView findByRequestKey(long tenant,String requestKey) {
        List<JobView> rows=jdbc.query("SELECT id,event_id,order_id,shop_id,printer_id,printer_name_snapshot AS printer_name,state,last_error,provider_job_id,attempts,parent_job_id,reprint_reason,payload_snapshot,create_time FROM yshop_order_print_job WHERE tenant_id=? AND request_key=?",
                this::map,tenant,requestKey);
        return rows.isEmpty()?null:rows.get(0);
    }

    private JobView findByRequestKeyForUpdate(long tenant,String requestKey) {
        List<JobView> rows=jdbc.query("SELECT id,event_id,order_id,shop_id,printer_id,printer_name_snapshot AS printer_name,state,last_error,provider_job_id,attempts,parent_job_id,reprint_reason,payload_snapshot,create_time FROM yshop_order_print_job WHERE tenant_id=? AND request_key=? FOR UPDATE",
                this::map,tenant,requestKey);
        return rows.isEmpty()?null:rows.get(0);
    }

    private JobView map(ResultSet rs,int row) throws SQLException {
        Timestamp createTime=rs.getTimestamp("create_time");
        Object printerValue=rs.getObject("printer_id"),parentValue=rs.getObject("parent_job_id");
        return new JobView(rs.getLong("id"),rs.getLong("event_id"),rs.getLong("order_id"),rs.getLong("shop_id"),
                printerValue==null?null:((Number)printerValue).longValue(),rs.getString("printer_name"),rs.getString("state"),
                rs.getString("last_error"),rs.getString("provider_job_id"),rs.getInt("attempts"),parentValue==null?null:((Number)parentValue).longValue(),
                rs.getString("reprint_reason"),rs.getString("payload_snapshot"),createTime==null?null:createTime.toLocalDateTime());
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE", impossible); }
    }

    private static String maskPhones(String value) {
        return value.replaceAll("(1[3-9][0-9])[0-9]{4}([0-9]{4})", "$1****$2");
    }

    public record ReprintRequest(@NotNull Long jobId,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestKey,
            @NotBlank @Size(max=255) String reason) {}

    private record Parent(long id,long eventId,String eventKey,long orderId,long shopId,long printerId,String printerName,
            String providerCode,String deviceCode,String state,String payload) {}
}
