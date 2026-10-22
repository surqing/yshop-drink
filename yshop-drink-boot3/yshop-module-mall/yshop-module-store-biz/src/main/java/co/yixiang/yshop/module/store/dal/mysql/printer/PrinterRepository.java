package co.yixiang.yshop.module.store.dal.mysql.printer;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.module.store.service.printer.PrinterFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;

/** Every device, binding and job query is explicitly tenant scoped, including row locks. */
@Repository
public class PrinterRepository {
    private final JdbcTemplate jdbc;
    public PrinterRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public static class Device {
        public Long id, tenantId, shopId;
        public String name, providerCode, deviceCode, model, connectionType, encryptedConfig, configuredFields, shopName, lastError, authorizationOperation, authorizationOperationType, authorizationOperationRequestId, authorizationOperationPriorAuthorization;
        public boolean enabled, deleted;
        public Authorization authorization;
        public DeviceState state;
        public LocalDateTime stateCheckedAt, createTime, authorizationOperationAt;
    }
    private Device map(ResultSet rs, int row) throws SQLException {
        Device d = new Device();
        d.id=rs.getLong("id"); d.tenantId=rs.getLong("tenant_id"); d.name=rs.getString("name");
        d.providerCode=rs.getString("provider_code"); d.deviceCode=rs.getString("device_code");
        d.model=rs.getString("model"); d.connectionType=rs.getString("connection_type");
        d.encryptedConfig=rs.getString("encrypted_config"); d.enabled=rs.getBoolean("enabled");
        d.deleted=rs.getBoolean("deleted"); d.authorization=Authorization.valueOf(rs.getString("authorization"));
        d.state=DeviceState.valueOf(rs.getString("device_state")); d.lastError=rs.getString("last_error");
        d.stateCheckedAt=time(rs,"state_checked_at"); d.createTime=time(rs,"create_time");
        d.configuredFields=rs.getString("configured_fields"); d.authorizationOperation=rs.getString("authorization_operation");
        d.authorizationOperationType=rs.getString("authorization_operation_type");
        d.authorizationOperationRequestId=rs.getString("authorization_operation_request_id");
        d.authorizationOperationPriorAuthorization=rs.getString("authorization_operation_prior_authorization");
        d.authorizationOperationAt=time(rs,"authorization_operation_at");
        return d;
    }
    private LocalDateTime time(ResultSet rs,String column) throws SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toLocalDateTime();
    }
    public Device require(long tenant, long id, boolean lock) {
        return require(tenant,id,lock,false);
    }
    public Device requireIncludingDeleted(long tenant,long id,boolean lock) {
        return require(tenant,id,lock,true);
    }
    private Device require(long tenant,long id,boolean lock,boolean includeDeleted) {
        List<Device> rows=jdbc.query("SELECT * FROM yshop_printer WHERE tenant_id=? AND id=?"+(includeDeleted?"":" AND deleted=0")
                +(lock?" FOR UPDATE":""),this::map,tenant,id);
        if(rows.isEmpty()) throw new PrinterFailure("PRINTER_NOT_FOUND",false);
        Device d=rows.get(0); fillBinding(d); return d;
    }
    private void fillBinding(Device d) {
        jdbc.query("SELECT b.shop_id,s.name FROM yshop_printer_binding b LEFT JOIN yshop_store_shop s "
                +"ON s.id=b.shop_id AND s.deleted=0 WHERE b.tenant_id=? AND b.printer_id=?",rs->{
                    d.shopId=rs.getLong("shop_id"); d.shopName=rs.getString("name");
                },d.tenantId,d.id);
    }
    public Device findIdentity(long tenant,String provider,String code) {
        List<Device> rows=jdbc.query("SELECT * FROM yshop_printer WHERE tenant_id=? AND provider_code=? AND device_code=? FOR UPDATE",
                this::map,tenant,provider,code);
        return rows.isEmpty()?null:rows.get(0);
    }
    public PageResult<Device> page(long tenant,int pageNo,int pageSize,String name,Long shopId) {
        return page(tenant,pageNo,pageSize,name,shopId,false);
    }
    public PageResult<Device> page(long tenant,int pageNo,int pageSize,String name,Long shopId,boolean deleted) {
        String where=" FROM yshop_printer p WHERE p.tenant_id=? AND p.deleted=?";
        List<Object> args=new ArrayList<>(List.of(tenant,deleted));
        if(name!=null&&!name.isBlank()) { where+=" AND p.name LIKE ?"; args.add("%"+name+"%"); }
        if(shopId!=null) { where+=" AND EXISTS (SELECT 1 FROM yshop_printer_binding b WHERE b.tenant_id=p.tenant_id AND b.printer_id=p.id AND b.shop_id=?)"; args.add(shopId); }
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());
        args.add(pageSize); args.add((pageNo-1)*pageSize);
        List<Device> list=jdbc.query("SELECT p.*"+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",this::map,args.toArray());
        list.forEach(this::fillBinding); return new PageResult<>(list,total);
    }
    public long insert(Device d,long actor) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(c->{
            PreparedStatement ps=c.prepareStatement("INSERT INTO yshop_printer "
                    +"(tenant_id,name,provider_code,device_code,model,connection_type,enabled,encrypted_config,configured_fields,creator,updater) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            Object[] args={d.tenantId,d.name,d.providerCode,d.deviceCode,d.model,d.connectionType,d.enabled,d.encryptedConfig,d.configuredFields,String.valueOf(actor),String.valueOf(actor)};
            for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); return ps;
        },key); return Objects.requireNonNull(key.getKey()).longValue();
    }
    public void save(Device d,long actor) {
        jdbc.update("UPDATE yshop_printer SET name=?,model=?,enabled=?,encrypted_config=?,configured_fields=?,deleted=0,updater=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                d.name,d.model,d.enabled,d.encryptedConfig,d.configuredFields,String.valueOf(actor),d.tenantId,d.id);
    }
    public void authorization(Device d,Authorization state,String error,long actor) {
        jdbc.update("UPDATE yshop_printer SET authorization=?,authorization_operation=NULL,authorization_operation_at=NULL,authorization_operation_type=NULL,authorization_operation_request_id=NULL,authorization_operation_prior_authorization=NULL,last_error=?,updater=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                state.name(),error,String.valueOf(actor),d.tenantId,d.id);
    }
    public void beginAuthorization(Device d,String operation,String operationType,long actor) {
        jdbc.update("UPDATE yshop_printer SET authorization='UNKNOWN',authorization_operation=?,authorization_operation_at=CURRENT_TIMESTAMP,authorization_operation_type=?,authorization_operation_request_id=NULL,authorization_operation_prior_authorization=?,last_error='RESULT_UNKNOWN',updater=? WHERE tenant_id=? AND id=?",
                operation,operationType,d.authorization.name(),String.valueOf(actor),d.tenantId,d.id);
    }
    /** Persist a short-lived exclusive provider operation without holding a DB lock during I/O. */
    public void beginOperation(Device d,String operation,String operationType,String requestId,long actor) {
        jdbc.update("UPDATE yshop_printer SET authorization_operation=?,authorization_operation_at=CURRENT_TIMESTAMP,authorization_operation_type=?,authorization_operation_request_id=?,authorization_operation_prior_authorization=?,updater=? WHERE tenant_id=? AND id=?",
                operation,operationType,requestId,d.authorization.name(),String.valueOf(actor),d.tenantId,d.id);
    }
    public void releaseOperation(Device d,String operation) {
        if(jdbc.update("UPDATE yshop_printer SET authorization_operation=NULL,authorization_operation_at=NULL,authorization_operation_type=NULL,authorization_operation_request_id=NULL,authorization_operation_prior_authorization=NULL "
                +"WHERE tenant_id=? AND id=? AND authorization_operation=?",d.tenantId,d.id,operation)!=1)
            throw new PrinterFailure("PRINTER_BUSY",false);
    }
    public void status(Device d,DeviceState state,String error,LocalDateTime time) {
        jdbc.update("UPDATE yshop_printer SET device_state=?,state_checked_at=?,last_error=? WHERE tenant_id=? AND id=?",
                state.name(),time,error,d.tenantId,d.id);
    }
    public void finishStatusOperation(Device d,String operation,DeviceState state,String error,LocalDateTime time) {
        if(jdbc.update("UPDATE yshop_printer SET device_state=?,state_checked_at=?,last_error=?,authorization_operation=NULL,"
                +"authorization_operation_at=NULL,authorization_operation_type=NULL,authorization_operation_request_id=NULL,authorization_operation_prior_authorization=NULL WHERE tenant_id=? AND id=? AND authorization_operation=?",
                state.name(),time,error,d.tenantId,d.id,operation)!=1)
            throw new PrinterFailure("PRINTER_BUSY",false);
    }
    public PendingOperation pendingOperation(Device d) {
        if(d.authorizationOperation==null) return null;
        String type=d.authorizationOperationType,requestId=d.authorizationOperationRequestId;
        if(type==null) {
            List<String> submitting=jdbc.query("SELECT request_id FROM yshop_printer_test_job WHERE tenant_id=? AND printer_id=? AND state='SUBMITTING' ORDER BY id DESC LIMIT 1",
                    (rs,n)->rs.getString(1),d.tenantId,d.id);
            if(!submitting.isEmpty()) { type="TEST_PRINT"; requestId=submitting.get(0); }
            else type="LEGACY_PROVIDER_OPERATION";
        }
        return new PendingOperation(d.id,d.authorizationOperation,type,requestId,d.authorizationOperationAt);
    }
    public void resolveProviderOperation(Device d,String operation,String type,long actor) {
        boolean authorizationOperation=List.of("AUTHORIZE","DEAUTHORIZE","RECONNECT","LEGACY_PROVIDER_OPERATION").contains(type);
        boolean statusOperation="STATUS_REFRESH".equals(type);
        String authorization=authorizationOperation?Authorization.UNKNOWN.name():d.authorization.name();
        String state=authorizationOperation||statusOperation?DeviceState.UNKNOWN.name():d.state.name();
        if(jdbc.update("UPDATE yshop_printer SET authorization=?,device_state=?,state_checked_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE state_checked_at END,"
                +"last_error='RESULT_UNKNOWN',updater=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND authorization_operation=?",
                authorization,state,authorizationOperation||statusOperation,String.valueOf(actor),d.tenantId,d.id,operation)!=1)
            throw new PrinterFailure("PRINTER_OPERATION_TOKEN_MISMATCH",false);
    }
    public void insertOperationResolution(Device d,PendingOperation pending,String priorAuthorization,
            String priorState,String outcome,String reason,long actor) {
        jdbc.update("INSERT INTO yshop_printer_operation_resolution(tenant_id,printer_id,operation_token,operation_type,request_id,operation_at,"
                +"prior_authorization,prior_device_state,outcome,reason,sender_stopped,provider_work_settled,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,TRUE,TRUE,?)",
                d.tenantId,d.id,pending.operationToken(),pending.operationType(),pending.requestId(),pending.startedAt(),
                priorAuthorization,priorState,outcome,reason.trim(),actor);
    }
    public int recoverSubmittingTestJob(Device d,String requestId) {
        if(requestId==null) return 0;
        return jdbc.update("UPDATE yshop_printer_test_job SET state='UNKNOWN',provider_job_id=NULL,error_code='RESULT_UNKNOWN' "
                +"WHERE tenant_id=? AND printer_id=? AND request_id=? AND state='SUBMITTING'",d.tenantId,d.id,requestId);
    }
    public void clearOperation(Device d,String operation) {
        if(jdbc.update("UPDATE yshop_printer SET authorization_operation=NULL,authorization_operation_at=NULL,authorization_operation_type=NULL,authorization_operation_request_id=NULL,authorization_operation_prior_authorization=NULL "
                +"WHERE tenant_id=? AND id=? AND authorization_operation=?",d.tenantId,d.id,operation)!=1)
            throw new PrinterFailure("PRINTER_OPERATION_TOKEN_MISMATCH",false);
    }
    public void delete(Device d,long actor) {
        jdbc.update("UPDATE yshop_printer SET deleted=1,enabled=0,updater=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                String.valueOf(actor),d.tenantId,d.id);
    }
    public boolean lockShop(long shopId) {
        return !jdbc.queryForList("SELECT id FROM yshop_store_shop WHERE id=? AND deleted=0 FOR UPDATE",Long.class,shopId).isEmpty();
    }
    public List<ShopOption> shops() {
        return jdbc.query("SELECT id,name FROM yshop_store_shop WHERE deleted=0 ORDER BY id",(rs,n)->new ShopOption(rs.getLong(1),rs.getString(2)));
    }
    public void bind(Device d,long shopId,long actor) {
        jdbc.update("INSERT INTO yshop_printer_binding (printer_id,tenant_id,shop_id,creator) VALUES (?,?,?,?)",
                d.id,d.tenantId,shopId,String.valueOf(actor));
    }
    public void unbind(Device d) { jdbc.update("DELETE FROM yshop_printer_binding WHERE tenant_id=? AND printer_id=?",d.tenantId,d.id); }
    public void shopDeleted(long shopId) {
        // The caller holds the shop row lock. Discover candidates without locks,
        // then take printer locks in the same order as bind/unbind before touching
        // binding rows. A locking JOIN can lock binding rows first under some plans
        // and deadlock with unbind (printer row, then binding DELETE).
        List<Long> candidates=jdbc.query("SELECT printer_id FROM yshop_printer_binding WHERE shop_id=? ORDER BY printer_id",
                (rs,row)->rs.getLong(1),shopId);
        List<Long> bound=new ArrayList<>();
        for(long printerId:candidates) {
            List<Device> locked=jdbc.query("SELECT * FROM yshop_printer WHERE id=? FOR UPDATE",this::map,printerId);
            if(locked.isEmpty()) continue;
            Device device=locked.get(0);
            // Current read after acquiring the printer lock; stale snapshot rows
            // are discarded if a concurrent unbind already committed.
            if(jdbc.query("SELECT shop_id FROM yshop_printer_binding WHERE printer_id=? AND shop_id=? FOR UPDATE",
                    (rs,row)->rs.getLong(1),printerId,shopId).isEmpty()) continue;
            // The timestamp is diagnostic only. A provider call can remain live after
            // any lease expires, so shop deletion must fail closed while a token exists.
            if(device.authorizationOperation!=null)
                throw new PrinterFailure("PRINTER_BUSY",false);
            if(dispatchClaimed(printerId)) throw new PrinterFailure("PRINTER_BUSY",false);
            bound.add(printerId);
        }
        int expected=bound.size();
        if(jdbc.update("DELETE FROM yshop_printer_binding WHERE shop_id=?",shopId)!=expected)
            throw new IllegalStateException("SHOP_BINDING_CLEANUP_WRITE_FAILED");
    }
    public boolean dispatchClaimed(Device d) { return dispatchClaimed(d.tenantId,d.id); }
    private boolean dispatchClaimed(long tenant,long printer) {
        // This is deliberately a current locking read. Under MySQL REPEATABLE READ,
        // shop deletion may already have established an older consistent-read
        // snapshot before it locks the printer. A plain COUNT could then miss a
        // claim committed while that snapshot was open and remove the binding.
        return !jdbc.query("SELECT job_id FROM yshop_order_print_device_claim WHERE tenant_id=? AND printer_id=? FOR UPDATE",
                (rs,row)->rs.getLong(1),tenant,printer).isEmpty();
    }
    private boolean dispatchClaimed(long printer) {
        // Printer IDs are globally unique; caller holds its printer row lock.
        return !jdbc.query("SELECT job_id FROM yshop_order_print_device_claim WHERE printer_id=? FOR UPDATE",
                (rs,row)->rs.getLong(1),printer).isEmpty();
    }
    /** Lock and validate the durable worker lease before taking the device reservation. */
    public boolean lockActiveOrderPrintJob(long tenant,long job,String leaseToken) {
        return !jdbc.query("SELECT id FROM yshop_order_print_job WHERE tenant_id=? AND id=? AND state='SENDING' "
                +"AND lease_token=? AND lease_expires_at>CURRENT_TIMESTAMP FOR UPDATE",
                (rs,row)->rs.getLong(1),tenant,job,leaseToken).isEmpty();
    }
    /** Caller holds the printer row lock; the durable claim serializes all workers for this device. */
    public void claimOrderPrintDevice(Device d,long job,String leaseToken,long shopId) {
        try {
            jdbc.update("INSERT INTO yshop_order_print_device_claim(tenant_id,printer_id,job_id,lease_token,shop_id) "
                    +"VALUES(?,?,?,?,?)",d.tenantId,d.id,job,leaseToken,shopId);
        } catch(DuplicateKeyException busy) {
            throw new PrinterFailure("PRINTER_BUSY",false);
        }
    }
    public void releaseOrderPrintDevice(long tenant,long printer,long job,String leaseToken) {
        if(jdbc.update("DELETE FROM yshop_order_print_device_claim WHERE tenant_id=? AND printer_id=? AND job_id=? AND lease_token=?",
                tenant,printer,job,leaseToken)!=1)
            throw new PrinterFailure("PRINTER_DISPATCH_CLAIM_LOST",true);
    }
    public JobView job(long tenant,String requestId) {
        List<JobView> rows=jdbc.query("SELECT j.*,p.name AS printer_name FROM yshop_printer_test_job j JOIN yshop_printer p "
                +"ON p.id=j.printer_id AND p.tenant_id=j.tenant_id WHERE j.tenant_id=? AND j.request_id=?",this::mapJob,tenant,requestId);
        return rows.isEmpty()?null:rows.get(0);
    }
    private JobView mapJob(ResultSet rs,int n) throws SQLException {
        return new JobView(rs.getString("request_id"),rs.getLong("printer_id"),rs.getString("printer_name"),
                JobState.valueOf(rs.getString("state")),rs.getString("provider_job_id"),rs.getString("error_code"),time(rs,"create_time"));
    }
    public void insertJob(Device d,String requestId,long actor) {
        jdbc.update("INSERT INTO yshop_printer_test_job (tenant_id,request_id,printer_id,state,creator) VALUES (?,?,?,'SUBMITTING',?)",
                d.tenantId,requestId,d.id,String.valueOf(actor));
    }
    public void finishJob(long tenant,String requestId,JobState state,String providerJobId,String error) {
        jdbc.update("UPDATE yshop_printer_test_job SET state=?,provider_job_id=?,error_code=? WHERE tenant_id=? AND request_id=?",
                state.name(),providerJobId,error,tenant,requestId);
    }
    public PageResult<JobView> jobs(long tenant,long printer,int pageNo,int pageSize) {
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_test_job WHERE tenant_id=? AND printer_id=?",Long.class,tenant,printer);
        List<JobView> rows=jdbc.query("SELECT j.*,p.name AS printer_name FROM yshop_printer_test_job j JOIN yshop_printer p "
                +"ON p.id=j.printer_id AND p.tenant_id=j.tenant_id WHERE j.tenant_id=? AND j.printer_id=? ORDER BY j.id DESC LIMIT ? OFFSET ?",
                this::mapJob,tenant,printer,pageSize,(pageNo-1)*pageSize);
        return new PageResult<>(rows,count);
    }
    public void ensureToken(String key) {
        jdbc.update("INSERT INTO yshop_printer_provider_token (account_key) VALUES (?) ON DUPLICATE KEY UPDATE account_key=account_key",key);
    }
    public String lockToken(String key) {
        return jdbc.queryForObject("SELECT encrypted_token FROM yshop_printer_provider_token WHERE account_key=? FOR UPDATE",String.class,key);
    }
    public void token(String key,String encrypted) {
        jdbc.update("UPDATE yshop_printer_provider_token SET encrypted_token=?,update_time=CURRENT_TIMESTAMP WHERE account_key=?",encrypted,key);
    }
}
