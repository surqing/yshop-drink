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
    /** Applies the same fresh headquarters-role and tenant checks used by printer management. */
    public long requireHeadquartersTenant() { return tenant(); }
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
        // Provider work runs outside DB transactions and can outlive an arbitrary lease.
        // Never let age alone make a still-running external operation appear idle.
        if(d.authorizationOperation!=null)
            throw new PrinterFailure("PRINTER_BUSY",false);
        if(repo.dispatchClaimed(d)) throw new PrinterFailure("PRINTER_BUSY",false);
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
    /** Internal order-worker send path. The caller owns the durable job lease and has no user context. */
    OrderPrintAttempt sendOrderPrint(long tenantId,long printerId,long shopId,long jobId,String leaseToken,
            String requestId,Receipt receipt) {
        PrinterProvider.assertOutsideTransaction();
        Device d;
        try {
            d=repo.require(tenantId,printerId,false);
            if(!Objects.equals(d.shopId,shopId)) throw new PrinterFailure("PRINTER_NOT_BOUND",false);
            if(!d.enabled) throw new PrinterFailure("PRINTER_DISABLED",false);
            PrinterProvider provider=registry.require(d.providerCode);
            if(provider.info().requiresAuthorization()&&d.authorization!=Authorization.AUTHORIZED)
                throw new PrinterFailure("PRINTER_NOT_AUTHORIZED",false);
            if(d.state!=DeviceState.ONLINE) throw new PrinterFailure("DEVICE_NOT_ONLINE",false);
            if(!provider.info().capabilities().textPrint()) throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
        } catch(PrinterFailure failure) {
            return new OrderPrintAttempt(false,null,failure);
        }
        Prepared prepared=prepare(d.providerCode);
        if(prepared.failure()!=null) return new OrderPrintAttempt(false,null,prepared.failure());
        try {
            tx.executeWithoutResult(status->{
                // Lock the job lease first, then the device row. Binding mutations lock the
                // same device row; the committed claim below bridges the external-I/O gap.
                if(!repo.lockActiveOrderPrintJob(tenantId,jobId,leaseToken))
                    throw new PrinterFailure("WORKER_LEASE_EXPIRED",false);
                Device current=repo.require(tenantId,printerId,true);
                if(!Objects.equals(current.shopId,shopId)) throw new PrinterFailure("PRINTER_NOT_BOUND",false);
                idle(current);
                if(!current.enabled) throw new PrinterFailure("PRINTER_DISABLED",false);
                PrinterProvider currentProvider=registry.require(current.providerCode);
                if(currentProvider.info().requiresAuthorization()&&current.authorization!=Authorization.AUTHORIZED)
                    throw new PrinterFailure("PRINTER_NOT_AUTHORIZED",false);
                if(current.state!=DeviceState.ONLINE) throw new PrinterFailure("DEVICE_NOT_ONLINE",false);
                if(!currentProvider.info().capabilities().textPrint()) throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
                repo.claimOrderPrintDevice(current,jobId,leaseToken,shopId);
            });
        } catch(PrinterFailure failure) {
            return new OrderPrintAttempt(false,null,failure);
        }
        try {
            String origin=cn.hutool.crypto.digest.DigestUtil.sha256Hex(tenantId+":"+requestId);
            return new OrderPrintAttempt(true,prepared.session().print(d.deviceCode,origin,receipt),null);
        } catch(PrinterFailure failure) {
            return new OrderPrintAttempt(true,null,failure);
        } finally {
            tx.executeWithoutResult(status->repo.releaseOrderPrintDevice(tenantId,printerId,jobId,leaseToken));
        }
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
            String operationType=reconnect?"RECONNECT":revoke?"DEAUTHORIZE":"AUTHORIZE";
            repo.beginAuthorization(d,operation,operationType,actor);
            return new AuthorizationIntent(d.providerCode,d.authorization);
        });
        if(intent==null) return new OperationResult(revoke?Authorization.UNAUTHORIZED:Authorization.AUTHORIZED,null);
        // Preparation and all session I/O run between short transactions. The
        // operation token prevents device mutations while the provider is called.
        Prepared prepared=prepare(intent.providerCode());
        Device snapshot=repo.require(tenant,id,false);
        if(!operation.equals(snapshot.authorizationOperation)) throw new PrinterFailure("PRINTER_BUSY",false);
        if(expired(snapshot.authorizationOperationAt)) {
            return tx.execute(status->{
                Device d=repo.require(tenant,id,true);
                if(!operation.equals(d.authorizationOperation)) throw new PrinterFailure("PRINTER_BUSY",false);
                repo.authorization(d,Authorization.UNKNOWN,"RESULT_UNKNOWN",actor);
                return new OperationResult(Authorization.UNKNOWN,"RESULT_UNKNOWN");
            });
        }
        Authorization next; String error=null; DeviceState reconciledState=null;
        try {
            if(prepared.failure()!=null) throw prepared.failure();
            PrinterSession session=prepared.session();
            if(revoke&&snapshot.shopId!=null) throw new PrinterFailure("UNBIND_FIRST",false);
            boolean reconciled=false;
            if(!revoke&&(reconnect||intent.previous()==Authorization.UNKNOWN)
                    &&registry.require(snapshot.providerCode).info().capabilities().status()) {
                try {
                    DeviceState state=session.status(snapshot.deviceCode);
                    reconciled=state!=DeviceState.UNKNOWN;
                    if(reconciled) reconciledState=state;
                } catch(PrinterFailure ignored) { /* One registration is allowed by this explicit operation. */ }
            }
            // Status I/O may have consumed the remaining intent lifetime.
            if(!reconciled&&expired(snapshot.authorizationOperationAt)) throw new PrinterFailure("RESULT_UNKNOWN",true);
            if(revoke) session.deauthorize(snapshot.deviceCode);
            else if(!reconciled) session.authorize(snapshot.deviceCode,snapshot.name,config(snapshot));
            next=revoke?Authorization.UNAUTHORIZED:Authorization.AUTHORIZED;
        } catch(PrinterFailure ex) {
            next=reconnect||ex.uncertain()?Authorization.UNKNOWN:intent.previous(); error=ex.getMessage();
        }
        Authorization result=next; String resultError=error; DeviceState statusToPersist=reconciledState;
        return tx.execute(status->{
            Device d=repo.require(tenant,id,true);
            if(!operation.equals(d.authorizationOperation)) throw new PrinterFailure("PRINTER_BUSY",false);
            if(statusToPersist!=null) repo.status(d,statusToPersist,null,LocalDateTime.now());
            repo.authorization(d,result,resultError,actor);
            if(result==Authorization.UNAUTHORIZED) repo.status(d,DeviceState.UNKNOWN,null,LocalDateTime.now());
            return new OperationResult(result,resultError);
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
        long tenant=tenant(),actor=actor(); String operation=UUID.randomUUID().toString();
        String providerCode=tx.execute(transaction->{
            Device d=repo.require(tenant,id,true); idle(d);
            PrinterProvider provider=registry.require(d.providerCode);
            if(!provider.info().capabilities().status()) throw new PrinterFailure("UNSUPPORTED_OPERATION",false);
            repo.beginOperation(d,operation,"STATUS_REFRESH",null,actor); return d.providerCode;
        });
        Prepared prepared=prepare(providerCode);
        Device snapshot=repo.require(tenant,id,false);
        if(!operation.equals(snapshot.authorizationOperation)) throw new PrinterFailure("PRINTER_BUSY",false);
        DeviceState state=DeviceState.UNKNOWN; String error=null;
        if(expired(snapshot.authorizationOperationAt)) error="RESULT_UNKNOWN";
        else try {
            if(prepared.failure()!=null) throw prepared.failure();
            state=prepared.session().status(snapshot.deviceCode);
        } catch(PrinterFailure ex) { state=DeviceState.UNKNOWN; error=ex.getMessage(); }
        DeviceState resultState=state; String resultError=error; LocalDateTime checked=LocalDateTime.now();
        return tx.execute(transaction->{
            Device d=repo.require(tenant,id,true);
            repo.finishStatusOperation(d,operation,resultState,resultError,checked);
            return new StatusResult(resultState,checked,resultError);
        });
    }
    public JobView test(TestRequest request) {
        PrinterProvider.assertOutsideTransaction();
        long tenant=tenant(),actor=actor(); String operation=UUID.randomUUID().toString();
        // Commit intent BEFORE preparation/external I/O. A crash cannot erase the key.
        String providerCode=tx.execute(status->{
            Device d=repo.require(tenant,request.id(),true);
            JobView old=repo.job(tenant,request.requestId());
            if(old!=null) { sameDevice(old,request.id()); return null; }
            idle(d);
            ready(d,registry.require(d.providerCode));
            repo.beginOperation(d,operation,"TEST_PRINT",request.requestId(),actor);
            repo.insertJob(d,request.requestId(),actor); return d.providerCode;
        });
        if(providerCode==null) return getJob(request.id(),request.requestId());
        Prepared prepared=prepare(providerCode);
        Device snapshot=repo.require(tenant,request.id(),false);
        JobView job=repo.job(tenant,request.requestId());
        if(!operation.equals(snapshot.authorizationOperation)||job==null||job.state()!=JobState.SUBMITTING)
            return observed(job);
        if(expired(job.createTime())) {
            return tx.execute(status->{
                Device d=repo.require(tenant,request.id(),true);
                JobView current=repo.job(tenant,request.requestId());
                if(current!=null&&current.state()==JobState.SUBMITTING) {
                    repo.finishJob(tenant,request.requestId(),JobState.UNKNOWN,null,"RESULT_UNKNOWN");
                    if(operation.equals(d.authorizationOperation)) repo.releaseOperation(d,operation);
                }
                return repo.job(tenant,request.requestId());
            });
        }
        JobState state; String providerId=null,error=null;
        try {
            if(prepared.failure()!=null) throw prepared.failure();
            ready(snapshot,registry.require(snapshot.providerCode));
            Receipt receipt=new Receipt("打印机测试小票",List.of("设备："+snapshot.name,"门店："+(snapshot.shopName==null?"未绑定":snapshot.shopName),
                    "时间："+job.createTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    "测试编号："+request.requestId(),"中文 ABC 123 ￥1.00","仅供设备测试"),1);
            String origin=cn.hutool.crypto.digest.DigestUtil.sha256Hex(tenant+":"+request.requestId());
            providerId=prepared.session().print(snapshot.deviceCode,origin,receipt).providerJobId(); state=JobState.ACCEPTED;
        } catch(PrinterFailure ex) {
            state=prepared.failure()==null&&ex.uncertain()?JobState.UNKNOWN:JobState.FAILED; error=ex.getMessage();
        }
        JobState resultState=state; String resultProviderId=providerId,resultError=error;
        return tx.execute(status->{
            Device d=repo.require(tenant,request.id(),true);
            JobView current=repo.job(tenant,request.requestId());
            if(current==null) throw new PrinterFailure("PRINT_JOB_NOT_FOUND",false);
            if(current.state()!=JobState.SUBMITTING) return observed(current);
            if(!operation.equals(d.authorizationOperation)) {
                repo.finishJob(tenant,request.requestId(),JobState.UNKNOWN,null,"RESULT_UNKNOWN");
                return repo.job(tenant,request.requestId());
            }
            repo.finishJob(tenant,request.requestId(),resultState,resultProviderId,resultError);
            repo.releaseOperation(d,operation);
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
    /** Pending provider operations are visible to headquarters operators for explicit crash recovery. */
    public PendingOperation pendingOperation(long id) {
        Device device=repo.require(tenant(),id,false);
        return repo.pendingOperation(device);
    }
    /** Recovery requires an exact operation token and explicit human confirmation of both external facts. */
    public OperationResolutionResult resolveOperation(ResolveOperationRequest request) {
        if(!Boolean.TRUE.equals(request.senderStopped())||!Boolean.TRUE.equals(request.providerWorkSettled()))
            throw new PrinterFailure("RECOVERY_CONFIRMATION_REQUIRED",false);
        if(request.reason()==null||request.reason().trim().length()<5)
            throw new PrinterFailure("RECOVERY_REASON_REQUIRED",false);
        long tenant=tenant(),actor=actor();
        return tx.execute(status->{
            Device device=repo.require(tenant,request.id(),true);
            if(device.authorizationOperation==null||!request.operationToken().equals(device.authorizationOperation))
                throw new PrinterFailure("PRINTER_OPERATION_TOKEN_MISMATCH",false);
            PendingOperation pending=repo.pendingOperation(device);
            if(pending==null||pending.operationType()==null)
                throw new PrinterFailure("PRINTER_OPERATION_NOT_RECOVERABLE",false);
            String outcome=switch(pending.operationType()) {
                case "AUTHORIZE", "DEAUTHORIZE", "RECONNECT" -> "AUTHORIZATION_UNKNOWN";
                case "STATUS_REFRESH" -> "DEVICE_STATUS_UNKNOWN";
                case "TEST_PRINT" -> "TEST_PRINT_UNKNOWN";
                case "LEGACY_PROVIDER_OPERATION" -> "PROVIDER_RESULT_UNKNOWN";
                default -> throw new PrinterFailure("PRINTER_OPERATION_NOT_RECOVERABLE",false);
            };
            if("TEST_PRINT".equals(pending.operationType())&&repo.recoverSubmittingTestJob(device,pending.requestId())!=1)
                throw new PrinterFailure("PRINTER_OPERATION_NOT_RECOVERABLE",false);
            repo.insertOperationResolution(device,pending,device.authorization.name(),device.state.name(),outcome,request.reason(),actor);
            repo.resolveProviderOperation(device,request.operationToken(),pending.operationType(),actor);
            repo.clearOperation(device,request.operationToken());
            return new OperationResolutionResult(device.id,request.operationToken(),pending.operationType(),outcome,LocalDateTime.now());
        });
    }
    public PageResult<JobView> jobs(long id,int pageNo,int pageSize) {
        long tenant=tenant(); repo.requireIncludingDeleted(tenant,id,false);
        PageResult<JobView> page=repo.jobs(tenant,id,pageNo,pageSize);
        return new PageResult<>(page.getList().stream().map(this::observed).toList(),page.getTotal());
    }
    record OrderPrintAttempt(boolean submitted,PrintResult result,PrinterFailure failure) {}
}
