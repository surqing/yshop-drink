package e2esupport;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.WxMaUserService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.yixiang.yshop.server.YshopServerApplication;
import co.yixiang.yshop.module.order.service.payment.PaymentFinalizationService;
import co.yixiang.yshop.module.order.service.payment.attempt.PaymentAttemptService;
import co.yixiang.yshop.module.pay.callback.PaymentSuccessEvent;
import co.yixiang.yshop.module.system.framework.sms.core.client.*;
import co.yixiang.yshop.module.system.framework.sms.core.client.dto.*;
import co.yixiang.yshop.module.system.framework.sms.core.property.SmsChannelProperties;
import co.yixiang.yshop.framework.common.core.KeyValue;
import co.yixiang.yshop.framework.common.enums.WebFilterOrderEnum;
import co.yixiang.yshop.framework.tenant.core.web.TenantContextWebFilter;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import com.egzosn.pay.spring.boot.core.PayServiceManager;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** Compiled separately from src/test; never packaged in the production JAR. */
public final class OwnedE2eApplication {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String OWNER=System.getProperty("e2e.owner","");
    static final Path ROOT=Path.of(System.getProperty("e2e.root",""));
    static final Set<String> CODES=ConcurrentHashMap.newKeySet();
    static final java.util.concurrent.atomic.AtomicInteger PRINT_CALLS=new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger STATUS_CALLS=new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicReference<co.yixiang.yshop.module.store.service.printer.PrinterModels.DeviceState> PRINTER_STATE=
            new java.util.concurrent.atomic.AtomicReference<>(co.yixiang.yshop.module.store.service.printer.PrinterModels.DeviceState.ONLINE);

    public static void main(String[] args) throws Exception {
        if(!OWNER.matches("[a-f0-9]{32}") || !ROOT.resolve("commands-"+OWNER).toFile().isDirectory())
            throw new IllegalStateException("OWNED_TEST_ROOT_REQUIRED");
        SpringApplication application=new SpringApplication(YshopServerApplication.class,ExternalBoundaries.class);
        ConfigurableApplicationContext context=application.run(args);
        var db=context.getBean(JdbcTemplate.class);
        String schema=db.queryForObject("SELECT DATABASE()",String.class);
        if(!("yshop_quality_business_"+OWNER.substring(0,16)).equals(schema)) {
            context.close();throw new IllegalStateException("OWNED_DATABASE_REQUIRED");
        }
        if(Boolean.parseBoolean(context.getEnvironment().getProperty("yshop.pay.wechat-v3.enabled","false"))
           || Boolean.parseBoolean(context.getEnvironment().getProperty("yshop.pay.wechat-v3.reconciliation-enabled","false"))) {
            context.close();throw new IllegalStateException("FINANCIAL_FREEZE_REQUIRED");
        }
        var mp=context.getBean(WxMpService.class);
        var payment=context.getBean(PayServiceManager.class);
        if(!Proxy.isProxyClass(mp.getClass()) || !Proxy.isProxyClass(payment.getClass())) {
            context.close();throw new IllegalStateException("PROVIDER_BOUNDARIES_NOT_BOUND");
        }
        assertDenied(()->mp.getOAuth2Service());
        assertDenied(()->payment.getOrderInfo(null));
        var executor=Executors.newSingleThreadScheduledExecutor(work -> {var t=new Thread(work,"e2e-command-mailbox");t.setDaemon(true);return t;});
        var events=new ConcurrentHashMap<String,PaymentSuccessEvent>();
        executor.scheduleWithFixedDelay(()->processCommands(context,db,events),0,100,TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdownNow));
        write(ROOT.resolve("support-ready.json"),Map.of("owner",OWNER,"status","PASSED","substitutes",List.of("mini-provider","sms-transport","mp-deny","payment-provider-deny"),"unknownProviderOperationsDenied",true));
    }

    @Configuration(proxyBeanMethods=false)
    public static class ExternalBoundaries {
        @Bean
        @ConditionalOnProperty(prefix="info",name="e2eTestSupport",havingValue="b3-tenant-context")
        FilterRegistrationBean<TenantContextWebFilter> b3TenantContextFilter() {
            FilterRegistrationBean<TenantContextWebFilter> registration=new FilterRegistrationBean<>();
            registration.setFilter(new TenantContextWebFilter());
            registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
            return registration;
        }

        @Bean co.yixiang.yshop.module.store.service.printer.PrinterProvider ownedPrinterProvider() {
            return new OwnedPrinter();
        }
        @Bean static BeanPostProcessor providerBoundaries() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(bean instanceof WxMaService) return miniProvider();
                    if(bean instanceof SmsClientFactory) return smsFactory();
                    if(bean instanceof WxMpService) return denyProvider(WxMpService.class,"MP");
                    if(bean instanceof PayServiceManager) return denyProvider(PayServiceManager.class,"PAYMENT");
                    return bean;
                }
            };
        }
    }

    /** Test-classpath-only transport; never opens a socket or accepts real credentials. */
    static final class OwnedPrinter implements co.yixiang.yshop.module.store.service.printer.PrinterProvider,
            co.yixiang.yshop.module.store.service.printer.PrinterSession {
        public co.yixiang.yshop.module.store.service.printer.PrinterModels.ProviderInfo info() {
            return new co.yixiang.yshop.module.store.service.printer.PrinterModels.ProviderInfo("OWNED_TEST","Owned synthetic printer",
                List.of("LAN"),List.of(),new co.yixiang.yshop.module.store.service.printer.PrinterModels.Capabilities(false,false,true,true),true,false);
        }
        public void validate(String id,String type,Map<String,String> configuration) {
            if(!"LAN".equals(type) || !configuration.isEmpty())throw new IllegalArgumentException("OWNED_PRINTER_ONLY");
        }
        public co.yixiang.yshop.module.store.service.printer.PrinterSession openSession() {
            co.yixiang.yshop.module.store.service.printer.PrinterProvider.assertOutsideTransaction();return this;
        }
        public void authorize(String id,String name,Map<String,String> configuration) {throw new IllegalStateException("OWNED_PRINTER_NO_AUTH");}
        public void deauthorize(String id) {throw new IllegalStateException("OWNED_PRINTER_NO_AUTH");}
        public co.yixiang.yshop.module.store.service.printer.PrinterModels.DeviceState status(String id) {
            STATUS_CALLS.incrementAndGet();return PRINTER_STATE.get();
        }
        public co.yixiang.yshop.module.store.service.printer.PrinterModels.PrintResult print(String id,String request,
                co.yixiang.yshop.module.store.service.printer.PrinterModels.Receipt receipt) {
            PRINT_CALLS.incrementAndGet();
            throw new co.yixiang.yshop.module.store.service.printer.PrinterFailure("PROVIDER_TIMEOUT",true);
        }
    }

    static <T> T denyProvider(Class<T> type,String provider) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args);
            throw new IllegalStateException("UNEXPECTED_"+provider+"_PROVIDER_OPERATION");
        }));
    }

    static void assertDenied(Runnable call) {
        try {call.run();throw new IllegalStateException("PROVIDER_BOUNDARY_ACCEPTED_UNKNOWN_OPERATION");}
        catch(IllegalStateException expected) {if(!expected.getMessage().startsWith("UNEXPECTED_"))throw expected;}
    }

    static WxMaService miniProvider() {
        WxMaUserService user=(WxMaUserService)Proxy.newProxyInstance(WxMaUserService.class.getClassLoader(),new Class[]{WxMaUserService.class},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args);
            if(!Set.of("getSessionInfo","getNewPhoneNoInfo").contains(method.getName()))
                throw new IllegalStateException("UNEXPECTED_MINI_PROVIDER_OPERATION");
            String code=String.valueOf(args[0]);
            String prefix=method.getName().equals("getSessionInfo")?"e2e-login-p":"e2e-phone-p";
            if(!code.matches(prefix+"[1-3]-[a-f0-9]{32}") || !CODES.add(code)) {
                WxError error=new WxError();error.setErrorCode(40029);error.setErrorMsg("synthetic invalid or consumed proof");
                throw new WxErrorException(error);
            }
            int principal=code.charAt(prefix.length())-'0';
            if(method.getName().equals("getSessionInfo")) {
                var session=new WxMaJscode2SessionResult();session.setOpenid("synthetic-mini-"+(100+principal));session.setSessionKey("synthetic-key");return session;
            }
            var number=new WxMaPhoneNumberInfo();number.setPurePhoneNumber("1380000000"+principal);return number;
        });
        return (WxMaService)Proxy.newProxyInstance(WxMaService.class.getClassLoader(),new Class[]{WxMaService.class},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args);
            if(method.getName().equals("getUserService"))return user;
            throw new IllegalStateException("UNEXPECTED_MINI_PROVIDER_OPERATION");
        });
    }

    static Object objectMethod(Object proxy,Method method,Object[] args) {
        return switch(method.getName()) {
            case "toString" -> "OwnedE2eExternalBoundary";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy==args[0];
            default -> throw new IllegalStateException("UNEXPECTED_OBJECT_METHOD");
        };
    }

    static SmsClientFactory smsFactory() {
        SmsClient client=new SmsClient() {
            public Long getId(){return 901L;}
            public SmsSendRespDTO sendSms(Long logId,String mobile,String template,List<KeyValue<String,Object>> params) throws Exception {
                if(!mobile.matches("1380000000[1-3]") || !template.equals("e2e-login"))throw new IllegalStateException("UNEXPECTED_SMS_PROVIDER_OPERATION");
                String code=params.stream().filter(value->value.getKey().equals("code")).findFirst().orElseThrow().getValue().toString();
                if(!code.matches("[0-9]{6}"))throw new IllegalStateException("INVALID_SYNTHETIC_SMS");
                write(ROOT.resolve("outbox").resolve(mobile+".json"),Map.of("owner",OWNER,"mobile",mobile,"code",code,"logId",logId));
                return new SmsSendRespDTO().setSuccess(true).setApiRequestId("synthetic-"+logId).setSerialNo("synthetic-"+logId).setApiCode("OK").setApiMsg("owned outbox");
            }
            public List<SmsReceiveRespDTO> parseSmsReceiveStatus(String text){throw new IllegalStateException("UNEXPECTED_SMS_PROVIDER_OPERATION");}
            public SmsTemplateRespDTO getSmsTemplate(String id){throw new IllegalStateException("UNEXPECTED_SMS_PROVIDER_OPERATION");}
        };
        return new SmsClientFactory() {
            public SmsClient getSmsClient(Long id){if(id!=901L)throw new IllegalStateException("UNEXPECTED_SMS_CHANNEL");return client;}
            public SmsClient getSmsClient(String code){if(!"E2E".equals(code))throw new IllegalStateException("UNEXPECTED_SMS_CHANNEL");return client;}
            public void createOrUpdateSmsClient(SmsChannelProperties properties){if(properties.getId()!=901L || !"E2E".equals(properties.getCode()))throw new IllegalStateException("UNEXPECTED_SMS_CHANNEL");}
        };
    }

    static void processCommands(ConfigurableApplicationContext context,JdbcTemplate db,Map<String,PaymentSuccessEvent> events) {
        Path mailbox=ROOT.resolve("commands-"+OWNER);
        try(var requests=Files.list(mailbox.resolve("requests"))) {
            for(Path request:requests.filter(path->path.getFileName().toString().matches("[a-f0-9]{32}[.]json")).toList()) {
                Path response=mailbox.resolve("responses").resolve(request.getFileName());
                if(Files.exists(response))continue;
                String id=request.getFileName().toString().replace(".json","");
                Map<String,Object> reply=new LinkedHashMap<>();reply.put("owner",OWNER);reply.put("commandId",id);
                try {
                    JsonNode command=JSON.readTree(request.toFile());
                    if(!OWNER.equals(command.path("owner").asText()) || !id.equals(command.path("commandId").asText()))throw new IllegalArgumentException("FOREIGN_COMMAND");
                    String action=command.path("action").asText();
                    if(action.equals("printer-counters")) {
                        reply.put("printCalls",PRINT_CALLS.get());reply.put("statusCalls",STATUS_CALLS.get());
                    } else if(action.equals("printer-state")) {
                        String state=command.path("state").asText();
                        if(!Set.of("ONLINE","OFFLINE","PAPER_OUT","UNKNOWN").contains(state))throw new IllegalArgumentException("INVALID_TEST_PRINTER_STATE");
                        PRINTER_STATE.set(co.yixiang.yshop.module.store.service.printer.PrinterModels.DeviceState.valueOf(state));
                        reply.put("state",state);
                    } else if(action.equals("order-print-poll")) {
                        context.getBean(co.yixiang.yshop.module.store.service.printer.OrderPrintJobWorker.class).poll();
                        reply.put("printCalls",PRINT_CALLS.get());reply.put("statusCalls",STATUS_CALLS.get());
                    } else if(action.equals("simulate-print-crash")) {
                        long jobId=command.path("jobId").asLong();
                        if(jobId<=0)throw new IllegalArgumentException("INVALID_PRINT_JOB");
                        String leaseToken=UUID.randomUUID().toString();
                        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
                            List<Map<String,Object>> jobs=db.queryForList("SELECT tenant_id,shop_id,printer_id,state,purpose,lease_expires_at "
                                    +"FROM yshop_order_print_job WHERE id=? FOR UPDATE",jobId);
                            if(jobs.size()!=1)throw new IllegalArgumentException("OWNED_PRINT_JOB_REQUIRED");
                            Map<String,Object> job=jobs.get(0);
                            if(!"ORDER".equals(job.get("purpose"))||!"UNKNOWN".equals(job.get("state"))||job.get("lease_expires_at")!=null)
                                throw new IllegalArgumentException("UNKNOWN_UNLEASED_ORDER_JOB_REQUIRED");
                            Integer claims=db.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE tenant_id=? AND job_id=?",
                                    Integer.class,job.get("tenant_id"),jobId);
                            if(claims==null||claims!=0)throw new IllegalArgumentException("PRINT_CLAIM_ALREADY_PRESENT");
                            if(db.update("UPDATE yshop_order_print_job SET last_error='WORKER_LEASE_EXPIRED',lease_token=?,"
                                    +"lease_expires_at=NULL,next_attempt_at=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND state='UNKNOWN' AND purpose='ORDER'",
                                    leaseToken,jobId)!=1)throw new IllegalStateException("PRINT_JOB_FIXTURE_CONFLICT");
                            db.update("INSERT INTO yshop_order_print_device_claim(tenant_id,printer_id,job_id,lease_token,shop_id) VALUES(?,?,?,?,?)",
                                    job.get("tenant_id"),job.get("printer_id"),jobId,leaseToken,job.get("shop_id"));
                        });
                        reply.put("jobId",jobId);reply.put("claimCreated",true);reply.put("syntheticCrashAftermath",true);
                    } else if(action.equals("stats-boundary-times")) {
                        String startOrder=command.path("startOrderId").asText();
                        String endOrder=command.path("endOrderId").asText();
                        LocalDate from=LocalDate.parse(command.path("fromDate").asText());
                        LocalDate to=LocalDate.parse(command.path("toDate").asText());
                        if(!startOrder.matches("[A-Za-z0-9_-]{1,64}")||!endOrder.matches("[A-Za-z0-9_-]{1,64}")
                                ||startOrder.equals(endOrder)||!to.isAfter(from))
                            throw new IllegalArgumentException("INVALID_STATS_BOUNDARY_FIXTURE");
                        Integer eligible=db.queryForObject("SELECT COUNT(*) FROM yshop_store_order WHERE order_id IN (?,?) "
                                +"AND paid=1 AND shop_id IN (101,102) AND COALESCE(tenant_id,0)=0 AND deleted=0 "
                                +"AND create_time>=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY)",Integer.class,startOrder,endOrder);
                        if(eligible==null||eligible!=2)throw new IllegalArgumentException("OWNED_PAID_STATS_ORDERS_REQUIRED");
                        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
                            if(db.update("UPDATE yshop_store_order SET pay_time=? WHERE order_id=? AND paid=1 "
                                            +"AND shop_id IN (101,102) AND COALESCE(tenant_id,0)=0 AND deleted=0",
                                    from.atStartOfDay(),startOrder)!=1)
                                throw new IllegalStateException("START_BOUNDARY_ORDER_UPDATE_FAILED");
                            if(db.update("UPDATE yshop_store_order SET pay_time=? WHERE order_id=? AND paid=1 "
                                            +"AND shop_id IN (101,102) AND COALESCE(tenant_id,0)=0 AND deleted=0",
                                    to.atStartOfDay(),endOrder)!=1)
                                throw new IllegalStateException("END_BOUNDARY_ORDER_UPDATE_FAILED");
                        });
                        reply.put("startOrderId",startOrder);reply.put("startPayTime",from.atStartOfDay().toString());
                        reply.put("endOrderId",endOrder);reply.put("endPayTime",to.atStartOfDay().toString());
                    } else if(action.equals("token-lifetime")) {
                        int seconds=command.path("seconds").asInt();
                        if(seconds!=2 && seconds!=3600)throw new IllegalArgumentException("INVALID_TEST_TOKEN_LIFETIME");
                        db.update("UPDATE system_oauth2_client SET access_token_validity_seconds=? WHERE client_id='default'",seconds);
                        // OAuth2ClientService caches client data; clear via the application's cache manager.
                        var manager=context.getBean(org.springframework.cache.CacheManager.class);
                        manager.getCacheNames().forEach(name->{var cache=manager.getCache(name);if(cache!=null)cache.clear();});
                        reply.put("seconds",seconds);
                    } else if(action.equals("complete")) {
                        String order=command.path("orderId").asText();
                        if(!order.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("INVALID_ORDER");
                        PaymentSuccessEvent event=events.get(order);
                        if(event==null) {
                            Long member=db.queryForObject("SELECT uid FROM yshop_store_order WHERE order_id=? AND paid=0 AND ordering_version=1",Long.class,order);
                            if(member==null)throw new IllegalArgumentException("OWNED_PENDING_ORDER_REQUIRED");
                            String merchant="synthetic-"+OWNER.substring(0,16);
                            db.update("INSERT IGNORE INTO merchant_details(details_id,pay_type,appid,mch_id,seller,sign_type,is_test) VALUES(?,'wxPay','synthetic-app','synthetic-merchant','synthetic-seller','RSA',1)",merchant);
                            var attempt=context.getBean(PaymentAttemptService.class).createOrGet(member,order,PaymentSuccessEvent.Provider.WECHAT,merchant,"synthetic-completion");
                            event=new PaymentSuccessEvent(PaymentSuccessEvent.Provider.WECHAT,attempt.getMerchantDetailsId(),attempt.getProviderOrderReference(),"synthetic-"+attempt.getAttemptId(),attempt.getAmountCents(),attempt.getAppid(),attempt.getMerchantIdentity(),"SUCCESS",LocalDateTime.now());
                            events.put(order,event);
                        }
                        reply.put("result",context.getBean(PaymentFinalizationService.class).acceptAttemptVerified(event).name());
                        reply.put("providerRequests",0);
                    } else throw new IllegalArgumentException("UNKNOWN_TEST_ACTION");
                    reply.put("status","PASSED");
                } catch(Exception failure) {
                    reply.put("status","FAILED");reply.put("reasonType",failure.getClass().getSimpleName());
                    try{write(ROOT.resolve("command-failure-private.json"),Map.of("owner",OWNER,"exception",failure.toString()));}catch(Exception ignored){}
                }
                write(response,reply);
            }
        } catch(Exception ignored) { /* Parent command timeout and retained private artifacts surface failure. */ }
    }

    static void write(Path path,Object value) throws Exception {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(temporary,JSON.writeValueAsString(value));
        Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));
        Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
