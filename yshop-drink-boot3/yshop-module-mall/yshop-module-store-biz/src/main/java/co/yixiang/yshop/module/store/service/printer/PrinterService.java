package co.yixiang.yshop.module.store.service.printer;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.store.dal.mysql.printer.PrinterRepository;
import co.yixiang.yshop.module.store.dal.mysql.printer.PrinterRepository.Device;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;

@Service
public class PrinterService {
    private final PrinterRepository repo;
    private final PrinterProviderRegistry registry;
    private final PrinterSecretCipher cipher;
    private final TransactionTemplate tx;
    public PrinterService(PrinterRepository repo,PrinterProviderRegistry registry,PrinterSecretCipher cipher,TransactionTemplate tx) {
        this.repo=repo; this.registry=registry; this.cipher=cipher; this.tx=tx;
    }
    private long tenant() {
        var user=SecurityFrameworkUtils.getLoginUser();
        Long contextTenant=TenantContextHolder.getTenantId();
        // Tenant filters are disabled in the current single-tenant deployment. Always scope
        // by the verified token principal, and reject a conflicting context when one exists.
        if(user==null||user.getId()==null||!Long.valueOf(0).equals(user.getShopId())
                ||user.getTenantId()==null||(contextTenant!=null&&!contextTenant.equals(user.getTenantId())))
            throw new PrinterFailure("HEADQUARTERS_ONLY",false);
        return user.getTenantId();
    }
    private long actor() { return Objects.requireNonNull(SecurityFrameworkUtils.getLoginUserId()); }
    private String context(Device d) { return "printer:"+d.tenantId+":"+d.providerCode+":"+d.deviceCode; }
    private Map<String,String> config(Device d) {
        if(d.encryptedConfig==null) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    cipher.decrypt(d.encryptedConfig,context(d)),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));
        } catch(PrinterFailure ex) { throw ex; }
        catch(Exception ex) { throw new PrinterFailure("SECRET_UNAVAILABLE",false); }
    }
    private DeviceView view(Device d) {
        // Reads remain usable without a key: expose presence, never decrypted values.
        Set<String> fields=d.configuredFields==null?Set.of():new HashSet<>(JsonUtils.parseArray(d.configuredFields,String.class));
        return new DeviceView(d.id,d.name,d.providerCode,d.deviceCode,d.model,d.connectionType,d.enabled,
                d.authorization,d.state,d.stateCheckedAt,d.shopId,d.shopName,fields,d.lastError,d.createTime);
    }
    private void idle(Device d) {
        if(d.authorizationOperation!=null&&d.authorizationOperationAt!=null&&d.authorizationOperationAt.isAfter(LocalDateTime.now().minusMinutes(1)))
            throw new PrinterFailure("PRINTER_BUSY",false);
    }
    public List<ProviderInfo> providers() { tenant(); return registry.list(); }
    public List<ShopOption> shops() { tenant(); return repo.shops(); }
    public PageResult<DeviceView> page(int pageNo,int pageSize,String name,Long shopId) {
        PageResult<Device> page=repo.page(tenant(),pageNo,pageSize,name,shopId);
        return new PageResult<>(page.getList().stream().map(this::view).toList(),page.getTotal());
    }
    public DeviceView get(long id) { return view(repo.require(tenant(),id,false)); }
    public long save(SaveRequest request,boolean update) {
        long tenant=tenant(); long actor=actor();
        return Objects.requireNonNull(tx.execute(status->{
            PrinterProvider provider=registry.require(request.providerCode());
            String code=request.deviceCode().trim();
            Device d;
            if(update) {
                if(request.id()==null) throw new PrinterFailure("INVALID_CONFIGURATION",false);
                d=repo.require(tenant,request.id(),true);
                idle(d);
                if(!d.providerCode.equals(request.providerCode())||!d.deviceCode.equals(code)||!d.connectionType.equals(request.connectionType()))
                    throw new PrinterFailure("IDENTITY_IMMUTABLE",false);
            } else {
                d=repo.findIdentity(tenant,request.providerCode(),code);
                if(d!=null&&!d.deleted) throw new PrinterFailure("DUPLICATE_DEVICE",false);
                if(d==null) { d=new Device(); d.tenantId=tenant; d.providerCode=request.providerCode(); d.deviceCode=code;
                    d.connectionType=request.connectionType(); d.authorization=Authorization.UNAUTHORIZED; }
            }
            Map<String,String> existing=config(d), merged=new LinkedHashMap<>(existing);
            Set<String> allowed=new HashSet<>(); provider.info().configFields().forEach(field->allowed.add(field.key()));
            request.configuration().forEach((key,value)->{
                if(!allowed.contains(key)||value==null) throw new PrinterFailure("INVALID_CONFIGURATION",false);
                if(!value.isBlank()) merged.put(key,value.trim());
            });
            provider.validate(code,request.connectionType(),merged);
            if(!existing.equals(merged)&&d.authorization!=Authorization.UNAUTHORIZED)
                throw new PrinterFailure("DEAUTHORIZE_FIRST",false);
            d.name=request.name().trim(); d.model=request.model()==null?null:request.model().trim(); d.enabled=request.enabled();
            d.configuredFields=JsonUtils.toJsonString(merged.keySet());
            if(!existing.equals(merged)) d.encryptedConfig=merged.isEmpty()?null:cipher.encrypt(JsonUtils.toJsonString(merged),context(d));
            if(d.id==null) return repo.insert(d,actor);
            repo.save(d,actor); return d.id;
        }));
    }
    public void delete(long id) {
        long tenant=tenant(),actor=actor();
        tx.executeWithoutResult(status->{
            Device d=repo.require(tenant,id,true);
            idle(d);
            if(d.shopId!=null) throw new PrinterFailure("UNBIND_FIRST",false);
            if(d.authorization!=Authorization.UNAUTHORIZED) throw new PrinterFailure("DEAUTHORIZE_FIRST",false);
            repo.delete(d,actor);
        });
    }
    public void bind(BindingRequest request) {
        long tenant=tenant(),actor=actor();
        tx.executeWithoutResult(status->{
            // Shop first: same lock order as shop deletion; protects against delete/bind races.
            if(!repo.lockShop(request.shopId())) throw new PrinterFailure("SHOP_NOT_FOUND",false);
            Device d=repo.require(tenant,request.id(),true);
            idle(d);
            if(Objects.equals(d.shopId,request.shopId())) return;
            if(d.shopId!=null) throw new PrinterFailure("UNBIND_FIRST",false);
            repo.bind(d,request.shopId(),actor);
        });
    }
    public void unbind(long id) {
        long tenant=tenant(); tx.executeWithoutResult(status->{
            Device d=repo.require(tenant,id,true); idle(d); repo.unbind(d);
        });
    }
    public OperationResult authorize(long id,boolean revoke) {
        long tenant=tenant(),actor=actor();
        String operation=UUID.randomUUID().toString();
        Authorization previous=tx.execute(status->{
            Device d=repo.require(tenant,id,true); PrinterProvider provider=registry.require(d.providerCode);
            idle(d);
            if(revoke&&d.shopId!=null) throw new PrinterFailure("UNBIND_FIRST",false);
            if(revoke&&d.authorization==Authorization.UNAUTHORIZED||!revoke&&d.authorization==Authorization.AUTHORIZED) return null;
            if(revoke?!provider.info().capabilities().deauthorize():!provider.info().capabilities().authorize())
                throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
            if(!provider.info().configured()) throw new PrinterFailure("PROVIDER_NOT_CONFIGURED",false);
            repo.beginAuthorization(d,operation,actor); return d.authorization;
        });
        if(previous==null) return new OperationResult(revoke?Authorization.UNAUTHORIZED:Authorization.AUTHORIZED,null);
        return tx.execute(status->{
            Device d=repo.require(tenant,id,true); PrinterProvider provider=registry.require(d.providerCode);
            if(!operation.equals(d.authorizationOperation)) throw new PrinterFailure("PRINTER_BUSY",false);
            Authorization next; String error=null;
            try {
                if(revoke&&d.shopId!=null) throw new PrinterFailure("UNBIND_FIRST",false);
                boolean reconciled=false;
                if(!revoke&&previous==Authorization.UNKNOWN) {
                    try { reconciled=provider.status(d.deviceCode)!=DeviceState.UNKNOWN; }
                    catch(PrinterFailure ignored) { /* Retry registration only on explicit user action. */ }
                }
                if(revoke) provider.deauthorize(d.deviceCode);
                else if(!reconciled) provider.authorize(d.deviceCode,d.name,config(d));
                next=revoke?Authorization.UNAUTHORIZED:Authorization.AUTHORIZED;
            } catch(PrinterFailure ex) {
                next=ex.uncertain()?Authorization.UNKNOWN:previous; error=ex.getMessage();
            }
            repo.authorization(d,next,error,actor);
            if(next==Authorization.UNAUTHORIZED) repo.status(d,DeviceState.UNKNOWN,null,LocalDateTime.now());
            return new OperationResult(next,error);
        });
    }
    public StatusResult status(long id) {
        long tenant=tenant(); return tx.execute(transaction->{
            Device d=repo.require(tenant,id,true);
            idle(d);
            DeviceState state; String error=null;
            try {
                PrinterProvider provider=registry.require(d.providerCode);
                if(!provider.info().capabilities().status()) throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
                state=provider.status(d.deviceCode);
            }
            catch(PrinterFailure ex) { state=DeviceState.UNKNOWN; error=ex.getMessage(); }
            LocalDateTime checked=LocalDateTime.now(); repo.status(d,state,error,checked);
            return new StatusResult(state,checked,error);
        });
    }
    public JobView test(TestRequest request) {
        long tenant=tenant(),actor=actor();
        // Commit intent BEFORE external I/O. A crash cannot erase the idempotency key.
        Boolean created=tx.execute(status->{
            Device d=repo.require(tenant,request.id(),true);
            idle(d);
            JobView old=repo.job(tenant,request.requestId());
            if(old!=null) { sameDevice(old,request.id()); return false; }
            if(!d.enabled) throw new PrinterFailure("PRINTER_DISABLED",false);
            if(d.authorization!=Authorization.AUTHORIZED) throw new PrinterFailure("PRINTER_NOT_AUTHORIZED",false);
            repo.insertJob(d,request.requestId(),actor); return true;
        });
        if(!Boolean.TRUE.equals(created)) return observedJob(tenant,request);
        return tx.execute(status->{
            Device d=repo.require(tenant,request.id(),true);
            JobView job=repo.job(tenant,request.requestId());
            if(job.state()!=JobState.SUBMITTING) return job;
            JobState state; String providerId=null,error=null;
            try {
                if(!d.enabled) throw new PrinterFailure("PRINTER_DISABLED",false);
                if(d.authorization!=Authorization.AUTHORIZED) throw new PrinterFailure("PRINTER_NOT_AUTHORIZED",false);
                Receipt receipt=new Receipt("打印机测试小票",List.of("设备："+d.name,"门店："+(d.shopName==null?"未绑定":d.shopName),
                        "时间："+job.createTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                        "测试编号："+request.requestId(),"中文 ABC 123 ￥1.00","仅供设备测试"),1);
                String origin=cn.hutool.crypto.digest.DigestUtil.sha256Hex(tenant+":"+request.requestId());
                PrinterProvider provider=registry.require(d.providerCode);
                if(!provider.info().capabilities().textPrint()) throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
                providerId=provider.print(d.deviceCode,origin,receipt).providerJobId(); state=JobState.ACCEPTED;
            } catch(PrinterFailure ex) { state=ex.uncertain()?JobState.UNKNOWN:JobState.FAILED; error=ex.getMessage(); }
            repo.finishJob(tenant,request.requestId(),state,providerId,error);
            return repo.job(tenant,request.requestId());
        });
    }
    private void sameDevice(JobView job,long id) {
        if(!job.printerId().equals(id)) throw new PrinterFailure("REQUEST_CONFLICT",false);
    }
    private JobView observedJob(long tenant,TestRequest request) {
        return tx.execute(status->{
            repo.require(tenant,request.id(),true); JobView job=repo.job(tenant,request.requestId()); sameDevice(job,request.id());
            if(job.state()==JobState.SUBMITTING&&job.createTime().isBefore(LocalDateTime.now().minusMinutes(1))) {
                repo.finishJob(tenant,job.requestId(),JobState.UNKNOWN,null,"RESULT_UNKNOWN"); return repo.job(tenant,job.requestId());
            }
            return job;
        });
    }
    public PageResult<JobView> jobs(long id,int pageNo,int pageSize) {
        long tenant=tenant(); repo.require(tenant,id,false);
        PageResult<JobView> page=repo.jobs(tenant,id,pageNo,pageSize);
        return new PageResult<>(page.getList().stream().map(job->
                job.state()==JobState.SUBMITTING&&job.createTime().isBefore(LocalDateTime.now().minusMinutes(1))
                    ?new JobView(job.requestId(),job.printerId(),job.printerName(),JobState.UNKNOWN,job.providerJobId(),"RESULT_UNKNOWN",job.createTime())
                    :job).toList(),page.getTotal());
    }
}
