package co.yixiang.yshop.module.store.dal.mysql.printer;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.module.store.service.printer.PrinterFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
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
        public String name, providerCode, deviceCode, model, connectionType, encryptedConfig, configuredFields, shopName, lastError, authorizationOperation;
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
        jdbc.update("UPDATE yshop_printer SET authorization=?,authorization_operation=NULL,authorization_operation_at=NULL,last_error=?,updater=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                state.name(),error,String.valueOf(actor),d.tenantId,d.id);
    }
    public void beginAuthorization(Device d,String operation,long actor) {
        jdbc.update("UPDATE yshop_printer SET authorization='UNKNOWN',authorization_operation=?,authorization_operation_at=CURRENT_TIMESTAMP,last_error='RESULT_UNKNOWN',updater=? WHERE tenant_id=? AND id=?",
                operation,String.valueOf(actor),d.tenantId,d.id);
    }
    public void status(Device d,DeviceState state,String error,LocalDateTime time) {
        jdbc.update("UPDATE yshop_printer SET device_state=?,state_checked_at=?,last_error=? WHERE tenant_id=? AND id=?",
                state.name(),time,error,d.tenantId,d.id);
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
    public void shopDeleted(long shopId) { jdbc.update("DELETE FROM yshop_printer_binding WHERE shop_id=?",shopId); }
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
