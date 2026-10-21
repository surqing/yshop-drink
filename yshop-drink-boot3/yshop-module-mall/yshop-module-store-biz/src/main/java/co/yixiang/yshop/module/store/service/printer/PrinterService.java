package co.yixiang.yshop.module.store.service.printer;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.store.dal.mysql.printer.PrinterRepository;
import co.yixiang.yshop.module.store.dal.mysql.printer.PrinterRepository.Device;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
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
    private final StoreAccessService storeAccess;
    private final boolean tenantEnabled;
    public PrinterService(PrinterRepository repo,PrinterProviderRegistry registry,PrinterSecretCipher cipher,TransactionTemplate tx,
            StoreAccessService storeAccess,@Value("${yshop.tenant.enable:false}") boolean tenantEnabled) {
        this.repo=repo; this.registry=registry; this.cipher=cipher; this.tx=tx; this.storeAccess=storeAccess;
        this.tenantEnabled=tenantEnabled;
    }
    private long tenant() {
        var user=SecurityFrameworkUtils.getLoginUser();
        Long contextTenant=TenantContextHolder.getTenantId();
        try {
            storeAccess.requireHeadquarters();
        } catch (AccessDeniedException denied) {
            throw new PrinterFailure("HEADQUARTERS_ONLY",false);
        }
        if(user==null||user.getId()==null)
            throw new PrinterFailure("HEADQUARTERS_ONLY",false);
        // With tenant filters disabled, OAuth caches a null tenant while the database
        // defaults it to 0. Both identify the same single-tenant scope; never use a header
        // to select a tenant for that principal. Tenant-enabled deployments remain strict.
        if(user.getTenantId()==null) {
            if(tenantEnabled||contextTenant!=null) throw new PrinterFailure("HEADQUARTERS_ONLY",false);
            return 0L;
        }
        if((tenantEnabled&&contextTenant==null)||(contextTenant!=null&&!contextTenant.equals(user.getTenantId())))
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
        return new DeviceView(d.id,d.name,d.providerCode,d.deviceCode,d.model,d.connectionType,d.enabled,d.deleted,
                d.authorization,d.state,d.stateCheckedAt,d.shopId,d.shopName,fields,d.lastError,d.createTime);
    }
    private void idle(Device d) {
        if(d.authorizationOperation!=null&&d.authorizationOperationAt!=null&&d.authorizationOperationAt.isAfter(LocalDateTime.now().minusMinutes(1)))
            throw new PrinterFailure("PRINTER_BUSY",false);
    }
    public List<ProviderInfo> providers() { tenant(); return registry.list(); }
    public List<ShopOption> shops() { tenant(); return repo.shops(); }
    public PageResult<DeviceView> page(int pageNo,int pageSize,String name,Long shopId) {
        return page(pageNo,pageSize,name,shopId,false);
    }
    public PageResult<DeviceView> page(int pageNo,int pageSize,String name,Long shopId,boolean deleted) {
        PageResult<Device> page=repo.page(tenant(),pageNo,pageSize,name,shopId,deleted);
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
            if(!existing.equals(merged)&&provider.info().requiresAuthorization()&&d.authorization!=Authorization.UNAUTHORIZED)
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
            if(registry.require(d.providerCode).info().requiresAuthorization()&&d.authorization!=Authorization.UNAUTHORIZED)
                throw new PrinterFailure("DEAUTHORIZE_FIRST",false);
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
    private record AuthorizationIntent(String providerCode, Authorization previous) {}
    private record Prepared(PrinterSession session, PrinterFailure failure) {}
    private Prepared prepare(String providerCode) {
        PrinterProvider.assertOutsideTransaction();
        try { return new Prepared(registry.require(providerCode).openSession(),null); }
        catch(PrinterFailure ex) { return new Prepared(null,ex); }
    }
    private boolean expired(LocalDateTime created) {
        return created==null||!created.isAfter(LocalDateTime.now().minusMinutes(1));
    }
    private void ready(Device d,PrinterProvider provider) {
        if(!d.enabled) throw new PrinterFailure("PRINTER_DISABLED",false);
        if(provider.info().requiresAuthorization()&&d.authorization!=Authorization.AUTHORIZED)
            throw new PrinterFailure("PRINTER_NOT_AUTHORIZED",false);
        if(!provider.info().capabilities().textPrint()) throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
    }
    public OperationResult authorize(long id,boolean revoke) { return authorization(id,revoke,false); }
    public OperationResult reconnect(long id) { return authorization(id,false,true); }
    private OperationResult authorization(long id,boolean revoke,boolean reconnect) {
        PrinterProvider.assertOutsideTransaction();
        long tenant=tenant(),actor=actor();
        String operation=UUID.randomUUID().toString();
        AuthorizationIntent intent=tx.execute(status->{
            Device d=repo.require(tenant,id,true);
            PrinterProvider provider=registry.require(d.providerCode);
            idle(d);
            if(revoke&&d.shopId!=null) throw new PrinterFailure("UNBIND_FIRST",false);
            if(!reconnect&&((revoke&&d.authorization==Authorization.UNAUTHORIZED)
                    ||(!revoke&&d.authorization==Authorization.AUTHORIZED))) return null;
            if(revoke?!provider.info().capabilities().deauthorize():!provider.info().capabilities().authorize())
                throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
            if(!provider.info().configured()) throw new PrinterFailure("PROVIDER_NOT_CONFIGURED",false);
            if(!revoke&&!reconnect&&d.authorization==Authorization.UNAUTHORIZED) provider.validateAuthorization(config(d));
            repo.beginAuthorization(d,operation,actor);
            return new AuthorizationIntent(d.providerCode,d.authorization);
        });
        if(intent==null) return new OperationResult(revoke?Authorization.UNAUTHORIZED:Authorization.AUTHORIZED,null);
        // This stage owns at most one connection and no device row lock.
        Prepared prepared=prepare(intent.providerCode());
        return tx.execute(status->{
            Device d=repo.require(tenant,id,true);
            if(!operation.equals(d.authorizationOperation)) throw new PrinterFailure("PRINTER_BUSY",false);
            if(expired(d.authorizationOperationAt)) {
                repo.authorization(d,Authorization.UNKNOWN,"RESULT_UNKNOWN",actor);
                return new OperationResult(Authorization.UNKNOWN,"RESULT_UNKNOWN");
            }
            Authorization next; String error=null;
            try {
                if(prepared.failure()!=null) throw prepared.failure();
                PrinterSession session=prepared.session();
                if(revoke&&d.shopId!=null) throw new PrinterFailure("UNBIND_FIRST",false);
                boolean reconciled=false;
                if(!revoke&&(reconnect||intent.previous()==Authorization.UNKNOWN)
                        &&registry.require(d.providerCode).info().capabilities().status()) {
                    try {
                        DeviceState state=session.status(d.deviceCode);
                        reconciled=state!=DeviceState.UNKNOWN;
                        if(reconciled) repo.status(d,state,null,LocalDateTime.now());
                    } catch(PrinterFailure ignored) { /* One registration is allowed by this explicit operation. */ }
                }
                // Status I/O may have consumed the remaining intent lifetime.
                if(!reconciled&&expired(d.authorizationOperationAt)) throw new PrinterFailure("RESULT_UNKNOWN",true);
                if(revoke) session.deauthorize(d.deviceCode);
                else if(!reconciled) session.authorize(d.deviceCode,d.name,config(d));
                next=revoke?Authorization.UNAUTHORIZED:Authorization.AUTHORIZED;
            } catch(PrinterFailure ex) {
                next=reconnect||ex.uncertain()?Authorization.UNKNOWN:intent.previous(); error=ex.getMessage();
            }
            repo.authorization(d,next,error,actor);
            if(next==Authorization.UNAUTHORIZED) repo.status(d,DeviceState.UNKNOWN,null,LocalDateTime.now());
            return new OperationResult(next,error);
        });
    }
    /** Read only: status retrieval never prepares credentials, calls a provider, or writes SQL. */
    public StatusResult cachedStatus(long id) {
        Device d=repo.require(tenant(),id,false);
        return new StatusResult(d.state,d.stateCheckedAt,d.lastError);
    }
    /** Explicit user-requested refresh, exposed only through POST /status/refresh. */
    public StatusResult status(long id) {
        PrinterProvider.assertOutsideTransaction();
        long tenant=tenant();
        Device snapshot=repo.require(tenant,id,false);
        if(!registry.require(snapshot.providerCode).info().capabilities().status())
            throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
        Prepared prepared=prepare(snapshot.providerCode);
        return tx.execute(transaction->{
            Device d=repo.require(tenant,id,true); idle(d);
            DeviceState state; String error=null;
            try {
                if(prepared.failure()!=null) throw prepared.failure();
                state=prepared.session().status(d.deviceCode);
            } catch(PrinterFailure ex) { state=DeviceState.UNKNOWN; error=ex.getMessage(); }
            LocalDateTime checked=LocalDateTime.now(); repo.status(d,state,error,checked);
            return new StatusResult(state,checked,error);
        });
    }
    public JobView test(TestRequest request) {
        PrinterProvider.assertOutsideTransaction();
        long tenant=tenant(),actor=actor();
        // Commit intent BEFORE preparation/external I/O. A crash cannot erase the key.
        String providerCode=tx.execute(status->{
            Device d=repo.require(tenant,request.id(),true); idle(d);
            JobView old=repo.job(tenant,request.requestId());
            if(old!=null) { sameDevice(old,request.id()); return null; }
            ready(d,registry.require(d.providerCode));
            repo.insertJob(d,request.requestId(),actor); return d.providerCode;
        });
        if(providerCode==null) return getJob(request.id(),request.requestId());
        Prepared prepared=prepare(providerCode);
        return tx.execute(status->{
            Device d=repo.require(tenant,request.id(),true);
            JobView job=repo.job(tenant,request.requestId());
            if(job.state()!=JobState.SUBMITTING) return observed(job);
            if(expired(job.createTime())) {
                repo.finishJob(tenant,request.requestId(),JobState.UNKNOWN,null,"RESULT_UNKNOWN");
                return repo.job(tenant,request.requestId());
            }
            JobState state; String providerId=null,error=null;
            try {
                if(prepared.failure()!=null) throw prepared.failure();
                ready(d,registry.require(d.providerCode));
                Receipt receipt=new Receipt("打印机测试小票",List.of("设备："+d.name,"门店："+(d.shopName==null?"未绑定":d.shopName),
                        "时间："+job.createTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                        "测试编号："+request.requestId(),"中文 ABC 123 ￥1.00","仅供设备测试"),1);
                String origin=cn.hutool.crypto.digest.DigestUtil.sha256Hex(tenant+":"+request.requestId());
                providerId=prepared.session().print(d.deviceCode,origin,receipt).providerJobId(); state=JobState.ACCEPTED;
            } catch(PrinterFailure ex) {
                state=prepared.failure()==null&&ex.uncertain()?JobState.UNKNOWN:JobState.FAILED; error=ex.getMessage();
            }
            repo.finishJob(tenant,request.requestId(),state,providerId,error);
            return repo.job(tenant,request.requestId());
        });
    }
    private void sameDevice(JobView job,long id) {
        if(!job.printerId().equals(id)) throw new PrinterFailure("REQUEST_CONFLICT",false);
    }
    private JobView observed(JobView job) {
        if(job!=null&&job.state()==JobState.SUBMITTING&&expired(job.createTime()))
            return new JobView(job.requestId(),job.printerId(),job.printerName(),JobState.UNKNOWN,
                    job.providerJobId(),"RESULT_UNKNOWN",job.createTime());
        return job;
    }
    /** Read only: even a missing/stale request must never create or dispatch a print. */
    public JobView getJob(long id,String requestId) {
        long tenant=tenant(); repo.requireIncludingDeleted(tenant,id,false);
        JobView job=repo.job(tenant,requestId);
        if(job!=null) sameDevice(job,id);
        return observed(job);
    }
    public PageResult<JobView> jobs(long id,int pageNo,int pageSize) {
        long tenant=tenant(); repo.requireIncludingDeleted(tenant,id,false);
        PageResult<JobView> page=repo.jobs(tenant,id,pageNo,pageSize);
        return new PageResult<>(page.getList().stream().map(this::observed).toList(),page.getTotal());
    }
}
