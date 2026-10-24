package co.yixiang.yshop.module.store.service.printer;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
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
        if (id < 0) return eventState(tenant, -id);
        List<JobView> rows = jdbc.query("SELECT id,event_id,order_id,shop_id,printer_id,printer_name_snapshot AS printer_name,state,last_error,provider_job_id,attempts,parent_job_id,reprint_reason,payload_snapshot,create_time FROM yshop_order_print_job WHERE tenant_id=? AND id=?",
                this::map, tenant, id);
        if (rows.isEmpty()) throw new PrinterFailure("PRINT_JOB_NOT_FOUND", false);
        return rows.get(0);
    }

    public JobView refresh(long id) {
        long tenant = printerAccess.requireHeadquartersTenant();
        if (id < 0) {
            JobView event = eventState(tenant, -id);
            if ("PENDING".equals(event.state()) || "WAITING_DEVICE".equals(event.state())) {
                try {
                    worker.refreshEvent(tenant, -id);
                } catch (PrinterFailure failure) {
                    // The scheduler may have expanded this event after the read above.
                    if (!"PRINT_JOB_NOT_FOUND".equals(failure.getMessage())) throw failure;
                }
            }
        } else worker.refreshJob(tenant, id);
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
            Parent parent = jdbc.query("SELECT id,event_id,event_key,order_id,shop_id,printer_id,printer_name_snapshot,provider_code_snapshot,device_code_snapshot,state,last_error,payload_snapshot FROM yshop_order_print_job WHERE tenant_id=? AND id=? FOR UPDATE",
                    (rs, row) -> new Parent(rs.getLong("id"), rs.getLong("event_id"), rs.getString("event_key"), rs.getLong("order_id"),
                            rs.getLong("shop_id"), rs.getLong("printer_id"), rs.getString("printer_name_snapshot"),
                            rs.getString("provider_code_snapshot"), rs.getString("device_code_snapshot"), rs.getString("state"),
                            rs.getString("last_error"), rs.getString("payload_snapshot")), tenant, parentId).stream().findFirst().orElse(null);
            if (parent == null) throw new PrinterFailure("PRINT_JOB_NOT_FOUND", false);
            if (!List.of("ACCEPTED", "FAILED", "UNKNOWN").contains(parent.state())) throw new PrinterFailure("JOB_NOT_REPRINTABLE", false);
            if("UNKNOWN".equals(parent.state())) throw new PrinterFailure("PRINT_RECOVERY_REQUIRED",false);
            if("OPERATOR_CONFIRMED_PRINTED".equals(parent.lastError())) throw new PrinterFailure("PRINT_ALREADY_CONFIRMED",false);
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

    /** Resolve a crashed send only after its sender stopped and an operator verified the physical outcome. */
    public JobView resolveUnknown(ResolveUnknownRequest request) {
        long tenant=printerAccess.requireHeadquartersTenant();
        Long actor=SecurityFrameworkUtils.getLoginUserId();
        if(actor==null) throw new PrinterFailure("HEADQUARTERS_ONLY",false);
        boolean printed=request.printedConfirmed();
        if(!request.senderStopped()||request.notPrintedConfirmed()==printed)
            throw new PrinterFailure("PRINT_RECOVERY_CONFIRMATION_REQUIRED",false);
        String reason=maskPhones(request.reason().trim());
        String disposition=printed?"PRINTED_CONFIRMED":"NOT_PRINTED_CONFIRMED";
        String requestHash=hash(tenant+"|"+request.jobId()+"|"+disposition+"|"+reason);
        return tx.execute(status->{
            List<Resolution> prior=jdbc.query("SELECT job_id,request_hash FROM yshop_order_print_device_claim_resolution "
                    +"WHERE tenant_id=? AND request_key=? FOR UPDATE",
                    (rs,row)->new Resolution(rs.getLong("job_id"),rs.getString("request_hash")),tenant,request.requestKey());
            if(!prior.isEmpty()) {
                if(prior.get(0).jobId()!=request.jobId()||!requestHash.equals(prior.get(0).requestHash()))
                    throw new PrinterFailure("REQUEST_CONFLICT",false);
                return job(tenant,request.jobId());
            }

            // Convert an expired lease to UNKNOWN inside this transaction. A live
            // lease remains SENDING and cannot be released through this endpoint.
            worker.refreshJob(tenant,request.jobId());
            List<RecoveryTarget> targets=jdbc.query("SELECT id,printer_id,state,last_error,lease_token,lease_expires_at "
                    +"FROM yshop_order_print_job WHERE tenant_id=? AND id=? FOR UPDATE",
                    (rs,row)->new RecoveryTarget(rs.getLong("id"),rs.getLong("printer_id"),rs.getString("state"),
                            rs.getString("last_error"),rs.getString("lease_token"),rs.getTimestamp("lease_expires_at")),
                    tenant,request.jobId());
            if(targets.isEmpty()) throw new PrinterFailure("PRINT_JOB_NOT_FOUND",false);
            RecoveryTarget target=targets.get(0);
            if(!"UNKNOWN".equals(target.state())||target.leaseExpiresAt()!=null)
                throw new PrinterFailure("PRINT_WORKER_LEASE_ACTIVE",false);

            if(jdbc.query("SELECT id FROM yshop_printer WHERE tenant_id=? AND id=? FOR UPDATE",
                    (rs,row)->rs.getLong(1),tenant,target.printerId()).isEmpty())
                throw new PrinterFailure("PRINTER_NOT_FOUND",false);
            List<Claim> claims=jdbc.query("SELECT job_id,lease_token FROM yshop_order_print_device_claim "
                    +"WHERE tenant_id=? AND printer_id=? FOR UPDATE",
                    (rs,row)->new Claim(rs.getLong("job_id"),rs.getString("lease_token")),tenant,target.printerId());
            if(!claims.isEmpty()&&(claims.get(0).jobId()!=target.id()||!Objects.equals(claims.get(0).leaseToken(),target.leaseToken())))
                throw new PrinterFailure("PRINT_CLAIM_NOT_FOUND",false);

            String outcome=disposition+(claims.isEmpty()?"_NO_CLAIM":"_CLAIM_RELEASED");
            jdbc.update("INSERT INTO yshop_order_print_device_claim_resolution(tenant_id,printer_id,job_id,request_key,request_hash,outcome,claim_was_present,reason,actor_id,prior_error) "
                    +"VALUES(?,?,?,?,?,?,?, ?,?,?)",tenant,target.printerId(),target.id(),request.requestKey(),requestHash,outcome,
                    !claims.isEmpty(),reason,actor,target.lastError());
            String finalState=printed?"ACCEPTED":"FAILED";
            String finalError=printed?"OPERATOR_CONFIRMED_PRINTED":"OPERATOR_CONFIRMED_NOT_PRINTED";
            if(jdbc.update("UPDATE yshop_order_print_job SET state=?,last_error=?,"
                    +"lease_token=NULL,lease_expires_at=NULL,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND state='UNKNOWN'",
                    finalState,finalError,tenant,target.id())!=1)
                throw new PrinterFailure("PRINT_JOB_STATE_CONFLICT",false);
            if(!claims.isEmpty()&&jdbc.update("DELETE FROM yshop_order_print_device_claim WHERE tenant_id=? AND printer_id=? AND job_id=? AND lease_token=?",
                    tenant,target.printerId(),target.id(),target.leaseToken())!=1)
                throw new PrinterFailure("PRINTER_DISPATCH_CLAIM_LOST",true);
            return job(tenant,target.id());
        });
    }

    private JobView job(long tenant,long id) {
        List<JobView> rows=jdbc.query("SELECT id,event_id,order_id,shop_id,printer_id,printer_name_snapshot AS printer_name,state,last_error,provider_job_id,attempts,parent_job_id,reprint_reason,payload_snapshot,create_time "
                +"FROM yshop_order_print_job WHERE tenant_id=? AND id=?",this::map,tenant,id);
        if(rows.isEmpty()) throw new PrinterFailure("PRINT_JOB_NOT_FOUND",false);
        return rows.get(0);
    }

    private JobView eventState(long tenant, long eventId) {
        List<JobView> rows = jdbc.query("SELECT -id AS id,id AS event_id,order_id,shop_id,NULL AS printer_id,NULL AS printer_name,state,last_error,NULL AS provider_job_id,attempts,NULL AS parent_job_id,NULL AS reprint_reason,payload_snapshot,create_time FROM yshop_order_fulfillment_print_event WHERE tenant_id=? AND id=?",
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

    public record ResolveUnknownRequest(@NotNull Long jobId,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestKey,
            @NotBlank @Size(max=255) String reason,
            @AssertTrue boolean senderStopped,
            boolean notPrintedConfirmed,
            boolean printedConfirmed) {
        public ResolveUnknownRequest(Long jobId,String requestKey,String reason,boolean senderStopped,boolean notPrintedConfirmed) {
            this(jobId,requestKey,reason,senderStopped,notPrintedConfirmed,false);
        }
    }

    private record Parent(long id,long eventId,String eventKey,long orderId,long shopId,long printerId,String printerName,
            String providerCode,String deviceCode,String state,String lastError,String payload) {}
    private record Resolution(long jobId,String requestHash) {}
    private record RecoveryTarget(long id,long printerId,String state,String lastError,String leaseToken,Timestamp leaseExpiresAt) {}
    private record Claim(long jobId,String leaseToken) {}
}
