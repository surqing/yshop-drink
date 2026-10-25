package co.yixiang.yshop.module.store.printer;

import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import co.yixiang.yshop.module.store.dal.mysql.printer.PrinterRepository;
import co.yixiang.yshop.module.store.service.printer.*;
import co.yixiang.yshop.module.store.service.printer.yilianyun.YilianyunTokenStore;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import co.yixiang.yshop.module.store.service.storeshop.StoreShopServiceImpl;
import co.yixiang.yshop.module.store.service.storeshop.StoreShopService;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import co.yixiang.yshop.module.system.api.permission.PermissionApi;
import co.yixiang.yshop.module.system.api.user.AdminUserApi;
import org.springframework.security.access.AccessDeniedException;
import co.yixiang.yshop.module.store.dal.mysql.storeshop.StoreShopMapper;
import co.yixiang.yshop.module.store.dal.dataobject.storeshop.StoreShopDO;
import javax.sql.DataSource;
import com.alibaba.druid.pool.DruidDataSource;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL/transactions in H2 by default; exact same migration and tests run on isolated MySQL. */
class PrinterDatabaseTest {
    JdbcTemplate jdbc;
    PrinterRepository repo;
    PrinterService service;
    DataSourceTransactionManager manager;
    PrinterSecretCipher cipher;
    FakeProvider fake;
    PrinterProviderRegistry providers;
    final Set<String> headquartersRoles=new HashSet<>();
    final Map<Long,Long> printerAdminTenants=new ConcurrentHashMap<>();
    PermissionApi printerPermissions;
    AdminUserApi printerAdminUsers;
    StoreAccessService printerAccess;
    static boolean mysql() { return System.getenv("PRINTER_ACCEPTANCE_CONFIG")!=null; }
    @BeforeEach void setup() throws Exception {
        DataSource ds;
        if(mysql()) {
            ds=PrinterMigrationSupport.mysql().getDataSource();
        } else {
            JdbcDataSource h2=new JdbcDataSource(); h2.setURL("jdbc:h2:mem:printer_"+UUID.randomUUID()+";MODE=MySQL;NON_KEYWORDS=AUTHORIZATION;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"); ds=h2;
        }
        jdbc=new JdbcTemplate(ds); manager=new DataSourceTransactionManager(ds); repo=new PrinterRepository(jdbc);
        fixture();
        jdbc.update("UPDATE yshop_store_shop SET deleted=0");
        applyMigration(); applyMigration();
        jdbc.update("DELETE FROM yshop_printer_operation_resolution");
        jdbc.update("DELETE FROM yshop_order_print_device_claim_resolution"); jdbc.update("DELETE FROM yshop_order_print_device_claim"); jdbc.update("DELETE FROM yshop_order_print_job"); jdbc.update("DELETE FROM yshop_order_fulfillment_print_event");
        jdbc.update("DELETE FROM yshop_printer_binding"); jdbc.update("DELETE FROM yshop_printer_test_job");
        jdbc.update("DELETE FROM yshop_printer_provider_token"); jdbc.update("DELETE FROM yshop_printer");
        cipher=new PrinterSecretCipher(Base64.getEncoder().encodeToString(new byte[32])); fake=new FakeProvider("YILIAN_YUN");
        headquartersRoles.clear(); headquartersRoles.add("business_headquarters");
        printerPermissions=mock(PermissionApi.class);
        when(printerPermissions.hasAnyRoles(anyLong(),any(String[].class))).thenAnswer(call->{
            return Arrays.stream(call.getArguments()).skip(1)
                    .flatMap(argument -> argument instanceof String[] roles ? Arrays.stream(roles) : java.util.stream.Stream.of((String) argument))
                    .anyMatch(headquartersRoles::contains);
        });
        printerAdminTenants.clear(); printerAdminTenants.put(1L,1L);
        printerAdminUsers=mock(AdminUserApi.class);
        when(printerAdminUsers.matchesUserTenant(any(),any())).thenAnswer(call->
                Objects.equals(printerAdminTenants.get(call.getArgument(0)),call.getArgument(1)));
        printerAccess=new StoreAccessService(jdbc,printerPermissions,printerAdminUsers);
        // A second transport deliberately needs no edits to the business service or the first provider.
        providers=new PrinterProviderRegistry(List.of(fake,new FakeProvider("SECOND_VENDOR")));
        service=new PrinterService(repo,providers,cipher,new TransactionTemplate(manager),printerAccess,false);
        login(1,0);
    }
    private void fixture() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS yshop_store_shop (id bigint PRIMARY KEY,name varchar(100),deleted boolean DEFAULT false)");
        String engine=mysql()?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":"";
        jdbc.execute("CREATE TABLE IF NOT EXISTS yshop_order_fulfillment_print_event (id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id bigint NOT NULL,order_id bigint NOT NULL,shop_id bigint NOT NULL,event_key varchar(128) NOT NULL,event_type varchar(32) NOT NULL,payload_snapshot text NOT NULL,state varchar(24) NOT NULL DEFAULT 'PENDING',attempts int NOT NULL DEFAULT 0,next_attempt_at timestamp NULL,last_error varchar(500) NULL,create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,event_key))"+engine);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM yshop_store_shop",Long.class)==0) {
            jdbc.update("INSERT INTO yshop_store_shop VALUES (1,'测试门店一',false),(2,'测试门店二',false)");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_menu (id bigint AUTO_INCREMENT PRIMARY KEY,name varchar(50),permission varchar(100) DEFAULT '',type int,sort int DEFAULT 0,parent_id bigint DEFAULT 0,path varchar(200),icon varchar(100),component varchar(255),component_name varchar(255),status int DEFAULT 0,visible bit DEFAULT 1,keep_alive bit DEFAULT 1,always_show bit DEFAULT 1,deleted bit DEFAULT 0)");
        for(long id=2309;id<=2313;id++) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM system_menu WHERE id=?",Long.class,id)==0)
                jdbc.update("INSERT INTO system_menu (id,name,parent_id,deleted) VALUES (?,'历史权限',2308,1)",id);
        }
    }
    private void applyMigration() throws Exception { PrinterMigrationSupport.migrate(jdbc,mysql()); }
    private OrderPrintJobWorker newWorker() { return new OrderPrintJobWorker(jdbc,manager,service,providers); }
    private long insertPrintEvent(String eventKey,long shopId) {
        long orderId=Math.abs(UUID.randomUUID().getMostSignificantBits());
        String payload="{\"schemaVersion\":1,\"orderId\":"+orderId+",\"orderCode\":\"synthetic-"+orderId+"\",\"shopName\":\"Synthetic frozen shop\",\"pickupNumber\":123,\"contactPhone\":\"138****1234\",\"totalQuantity\":2,\"paidAmount\":19.50,\"items\":[{\"title\":\"Synthetic tea\",\"quantity\":2,\"unitPrice\":9.75,\"specification\":\"large|less ice\",\"cartDetails\":\"{}\"}]}";
        jdbc.update("INSERT INTO yshop_order_fulfillment_print_event(tenant_id,order_id,shop_id,event_key,event_type,payload_snapshot,state,attempts,create_time,update_time) VALUES(1,?,?,?,'PICKUP_ACCEPTED',?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",orderId,shopId,eventKey,payload);
        return jdbc.queryForObject("SELECT id FROM yshop_order_fulfillment_print_event WHERE tenant_id=1 AND event_key=?",Long.class,eventKey);
    }
    private long readyPrinter(long shopId,String deviceCode) {
        long id=device(deviceCode);
        service.bind(new BindingRequest(id,shopId));
        jdbc.update("UPDATE yshop_printer SET authorization='AUTHORIZED',device_state='ONLINE',state_checked_at=CURRENT_TIMESTAMP WHERE tenant_id=1 AND id=?",id);
        return id;
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext(); TenantContextHolder.clear();
    }
    private void login(long tenant,long shop) {
        login(1L,tenant,shop);
    }
    private void login(long userId,long tenant,long shop) {
        LoginUser user=new LoginUser(); user.setId(userId); user.setUserType(2); user.setShopId(shop); user.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
        TenantContextHolder.setTenantId(tenant);
    }
    private SaveRequest request(Long id,String code,Map<String,String> configuration) {
        return new SaveRequest(id,"测试设备","YILIAN_YUN",code,"K4","CLOUD",true,configuration);
    }
    private long device(String code) { return service.save(request(null,code,Map.of("terminalKey","synthetic-device-key")),false); }

    @Test void disabledTenantModeDoesNotExposePendingOperationToForeignDatabaseTenant() {
        login(0,0);
        long id=device("4000010001");
        fake.crash=true;
        assertThrows(IllegalStateException.class,()->service.authorize(id,false));
        fake.crash=false;
        assertNotNull(service.pendingOperation(id));

        printerAdminTenants.put(910113L,901L);
        PrinterRepository observedRepo=spy(repo);
        PrinterService guarded=new PrinterService(observedRepo,providers,cipher,new TransactionTemplate(manager),printerAccess,false);
        try {
            login(910113L,0L,0L); // The cached OAuth claim has collapsed to database default tenant 0.
            TenantContextHolder.clear(); // Tenant auto-configuration is disabled in this profile.

            assertThrows(PrinterFailure.class,()->guarded.pendingOperation(id));
            PendingOperation pending=repo.pendingOperation(repo.require(0L,id,false));
            int providerCalls=fake.printCalls.get();
            assertThrows(PrinterFailure.class,()->guarded.resolveOperation(new ResolveOperationRequest(
                    id,pending.operationToken(),"阻止跨租户恢复操作",true,true)));
            verifyNoInteractions(observedRepo);
            assertEquals(providerCalls,fake.printCalls.get());
        } finally {
            printerAdminTenants.remove(910113L);
        }
    }

    @Test void zeroShopHintDoesNotGrantHeadquarters() {
        long id=device("4000000001"); headquartersRoles.clear(); login(1,0);
        PrinterRepository observedRepo=spy(repo);
        PrinterProviderRegistry observedRegistry=spy(new PrinterProviderRegistry(List.of(fake)));
        PrinterService guarded=new PrinterService(observedRepo,observedRegistry,cipher,new TransactionTemplate(manager),printerAccess,false);
        int sessions=fake.sessionCalls.get(),authorizations=fake.authorizeCalls.get();

        assertThrows(PrinterFailure.class,()->guarded.authorize(id,false));

        verifyNoInteractions(observedRepo,observedRegistry);
        assertEquals(sessions,fake.sessionCalls.get()); assertEquals(authorizations,fake.authorizeCalls.get());
    }

    @Test void revokedHeadquartersCannotUseCachedSession() {
        long id=device("4000000001"); login(1,0);
        PrinterRepository observedRepo=spy(repo);
        PrinterProviderRegistry observedRegistry=spy(new PrinterProviderRegistry(List.of(fake)));
        PrinterService guarded=new PrinterService(observedRepo,observedRegistry,cipher,new TransactionTemplate(manager),printerAccess,false);
        LoginUser cached=(LoginUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(id,guarded.get(id).id());
        clearInvocations(observedRepo,observedRegistry);
        headquartersRoles.clear();

        assertSame(cached,SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertThrows(PrinterFailure.class,()->guarded.get(id));

        verifyNoInteractions(observedRepo,observedRegistry);
    }

    @Test void explicitHeadquartersUsesRoleAndTenantScope() {
        long id=device("4000000001");
        login(1,1);

        assertEquals(id,service.get(id).id());
        assertEquals(1L,jdbc.queryForObject("SELECT tenant_id FROM yshop_printer WHERE id=?",Long.class,id));
    }
    @Test void migrationIsRepeatableAndRestoresOnlyPrinterMenu() {
        assertEquals("mall/store/printer/index",jdbc.queryForObject("SELECT component FROM system_menu WHERE id=2308",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT deleted+0 FROM system_menu WHERE id=2308",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT deleted+0 FROM system_menu WHERE id=2313",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM system_menu WHERE permission IN ('store:web-print:authorize','store:web-print:binding','store:web-print:test')",Integer.class));
    }

    @Test void providerSessionCallsRunOutsideDatabaseTransactions() {
        List<Boolean> transactionStates=new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<PrinterService> serviceRef=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong printerRef=new java.util.concurrent.atomic.AtomicLong();
        Runnable recordTransaction=()->transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
        PrinterSession session=new PrinterSession() {
            public void authorize(String code,String name,Map<String,String> configuration) { recordTransaction.run(); }
            public void deauthorize(String code) { recordTransaction.run(); }
            public DeviceState status(String code) {
                recordTransaction.run();
                assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->serviceRef.get().unbind(printerRef.get())).getMessage());
                return DeviceState.ONLINE;
            }
            public PrintResult print(String code,String requestId,Receipt receipt) {
                recordTransaction.run();
                assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->serviceRef.get().unbind(printerRef.get())).getMessage());
                return new PrintResult("synthetic-provider-job");
            }
        };
        PrinterProvider provider=new PrinterProvider() {
            public ProviderInfo info() { return new ProviderInfo("TX_BOUNDARY","Transaction boundary fixture",List.of("CLOUD"),
                    List.of(new Field("terminalKey","Key",true,true)),new Capabilities(true,true,true,true),true,true); }
            public void validate(String code,String connection,Map<String,String> configuration) {}
            public PrinterSession openSession() { recordTransaction.run(); return session; }
        };
        PrinterService local=new PrinterService(repo,new PrinterProviderRegistry(List.of(provider)),cipher,
                new TransactionTemplate(manager),printerAccess,false);
        long id=local.save(new SaveRequest(null,"transaction-boundary","TX_BOUNDARY","transaction-boundary-device","","CLOUD",true,
                Map.of("terminalKey","synthetic")),false);
        serviceRef.set(local); printerRef.set(id);

        assertEquals(Authorization.AUTHORIZED,local.authorize(id,false).authorization());
        assertEquals(DeviceState.ONLINE,local.status(id).state());
        assertEquals(JobState.ACCEPTED,local.test(new TestRequest(id,"transaction-boundary-0001")).state());
        assertEquals(Authorization.UNAUTHORIZED,local.authorize(id,true).authorization());

        assertEquals(List.of(false,false,false,false,false,false,false,false),transactionStates);
    }

    @Test void committedAcceptanceSurvivesWorkerRestart() {
        long event=insertPrintEvent("restart-event-0001",1L); readyPrinter(1L,"4000000990");
        var jobs=new OrderPrintJobService(jdbc,manager,service,newWorker());
        assertEquals(-event,jobs.page(1,20,1L).getList().get(0).id());
        assertEquals("PENDING",jobs.get(-event).state());
        newWorker().processPendingEvents();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Integer.class,event));
        newWorker().processPendingEvents();
        assertEquals("PROCESSED",jdbc.queryForObject("SELECT state FROM yshop_order_fulfillment_print_event WHERE id=?",String.class,event));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Integer.class,event));
        assertEquals(0,fake.printCalls.get());
    }

    @Test void duplicateEventCreatesOneJob() {
        long event=insertPrintEvent("duplicate-event-0001",1L); readyPrinter(1L,"4000000991");
        var worker=newWorker(); worker.processPendingEvents(); worker.processPendingEvents();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Integer.class,event));
        assertEquals(0,fake.printCalls.get());
    }

    @Test void missingDeviceIsVisibleAndRefreshDoesNotCallProvider() {
        long event=insertPrintEvent("waiting-device-event-0001",1L);
        var worker=newWorker(); worker.processPendingEvents();
        long sessions=fake.sessionCalls.get(),prints=fake.printCalls.get();
        assertEquals("WAITING_DEVICE",jdbc.queryForObject("SELECT state FROM yshop_order_fulfillment_print_event WHERE id=?",String.class,event));
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        var page=jobs.page(1,20,1L);
        assertEquals(1,page.getTotal());
        assertEquals(-event,page.getList().get(0).id());
        assertEquals("NO_BOUND_DEVICE",page.getList().get(0).errorCode());
        assertEquals("WAITING_DEVICE",jobs.refresh(-event).state());
        assertEquals(sessions,fake.sessionCalls.get()); assertEquals(prints,fake.printCalls.get());
    }

    @Test void refreshingRecoveredWaitingEventReturnsItsProcessedJobState() {
        long event=insertPrintEvent("recovered-device-event-0001",1L);
        var worker=newWorker(); worker.processPendingEvents();
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        assertEquals("WAITING_DEVICE",jobs.get(-event).state());
        readyPrinter(1L,"4000000996");

        var refreshed=jobs.refresh(-event);

        assertEquals("PROCESSED",refreshed.state());
        assertEquals(1,jobs.page(1,20,1L).getTotal());
        assertEquals(0,fake.sessionCalls.get());
        assertEquals(0,fake.printCalls.get());
    }

    @Test void expiredOrderPrintLeaseBecomesUnknownWithoutResend() {
        long event=insertPrintEvent("expired-order-print-0001",1L); readyPrinter(1L,"4000000994");
        var worker=newWorker(); worker.processPendingEvents();
        long job=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        jdbc.update("UPDATE yshop_order_print_job SET state='SENDING',attempts=1,lease_token='crashed-worker',lease_expires_at=? WHERE id=?",
                java.time.LocalDateTime.now().minusMinutes(2),job);
        assertEquals(1,worker.recoverExpiredJobs());
        worker.dispatchPendingJobs();
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,job));
        assertEquals("WORKER_LEASE_EXPIRED",jdbc.queryForObject("SELECT last_error FROM yshop_order_print_job WHERE id=?",String.class,job));
        assertEquals(0,fake.printCalls.get());
    }

    @Test void unexpectedProviderExceptionKeepsClaimUntilAuditedResolution() {
        long event=insertPrintEvent("provider-crash-claim-event-01",1L); long printer=readyPrinter(1L,"4000000996");
        var worker=newWorker(); worker.processPendingEvents();
        long job=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        fake.crash=true;
        assertThrows(IllegalStateException.class,worker::dispatchPendingJobs);
        assertEquals(1,fake.printCalls.get());
        assertEquals("SENDING",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,job));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE printer_id=?",Integer.class,printer));
        jdbc.update("UPDATE yshop_order_print_job SET lease_expires_at=? WHERE id=?",LocalDateTime.now().minusMinutes(2),job);
        assertEquals(1,worker.recoverExpiredJobs());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,job));
        assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->service.unbind(printer)).getMessage());
        fake.crash=false;
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        var resolved=jobs.resolveUnknown(new OrderPrintJobService.ResolveUnknownRequest(job,"crash-claim-resolve-001",
                "已停止旧发送进程并与打印端核实没有出纸",true,true));
        assertEquals("FAILED",resolved.state());
        assertEquals("NOT_PRINTED_CONFIRMED_CLAIM_RELEASED",jdbc.queryForObject(
                "SELECT outcome FROM yshop_order_print_device_claim_resolution WHERE tenant_id=1 AND job_id=?",String.class,job));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE printer_id=?",Integer.class,printer));
        worker.dispatchPendingJobs();
        assertEquals(1,fake.printCalls.get());
    }

    @Test void unknownNeverAutoResends() {
        long event=insertPrintEvent("unknown-event-0001",1L); long printer=readyPrinter(1L,"4000000992");
        var worker=newWorker(); worker.processPendingEvents();
        fake.printFailure=new PrinterFailure("PROVIDER_TIMEOUT",true);
        worker.dispatchPendingJobs();
        assertEquals(1,fake.printCalls.get());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",String.class,event));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim",Integer.class));
        assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->service.unbind(printer)).getMessage());
        long secondEvent=insertPrintEvent("unknown-device-blocked-event-01",1L);
        worker.processPendingEvents(); worker.dispatchPendingJobs();
        long secondJob=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,secondEvent);
        assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,secondJob));
        assertEquals(1,fake.printCalls.get());
        fake.printFailure=null;
        worker.processPendingEvents(); worker.recoverExpiredJobs(); worker.dispatchPendingJobs();
        assertEquals(1,fake.printCalls.get());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",String.class,event));
        long job=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        assertEquals("PRINT_RECOVERY_REQUIRED",assertThrows(PrinterFailure.class,()->jobs.reprint(
                new OrderPrintJobService.ReprintRequest(job,"unknown-direct-reprint-0001","结果待核对后重打"))).getMessage());
        var resolved=jobs.resolveUnknown(new OrderPrintJobService.ResolveUnknownRequest(job,"unknown-resolution-key-01",
                "确认厂商请求已结束且核实没有出纸",true,true));
        assertEquals("FAILED",resolved.state());
        assertEquals("NOT_PRINTED_CONFIRMED_CLAIM_RELEASED",jdbc.queryForObject(
                "SELECT outcome FROM yshop_order_print_device_claim_resolution WHERE job_id=?",String.class,job));
        jdbc.update("UPDATE yshop_order_print_job SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",secondJob);
        worker.dispatchPendingJobs();
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,secondJob));
        assertEquals(2,fake.printCalls.get());
        jobs.reprint(new OrderPrintJobService.ReprintRequest(job,"unknown-confirmed-reprint-01","核实未出纸后重打"));
        worker.dispatchPendingJobs();
        assertEquals(3,fake.printCalls.get());
    }

    @Test void expiredUnknownJobDoesNotAutoReleaseDeviceReservation() {
        long event=insertPrintEvent("expired-claim-unknown-0001",1L);
        long printer=readyPrinter(1L,"4000000995");
        var worker=newWorker(); worker.processPendingEvents();
        long job=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        String token="expired-dispatch-lease-0001";
        jdbc.update("UPDATE yshop_order_print_job SET state='SENDING',attempts=1,lease_token=?,lease_expires_at=? WHERE id=?",
                token,LocalDateTime.now().minusMinutes(2),job);
        jdbc.update("INSERT INTO yshop_order_print_device_claim(tenant_id,printer_id,job_id,lease_token,shop_id) VALUES(1,?,?,?,1)",
                printer,job,token);

        assertEquals(1,worker.recoverExpiredJobs());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,job));
        assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->service.unbind(printer)).getMessage());
        worker.dispatchPendingJobs();

        assertEquals(0,fake.printCalls.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE printer_id=?",Integer.class,printer));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,job));
    }

    @Test void expiredUnknownClaimRequiresAuditedOperatorConfirmationBeforeReprint() {
        long event=insertPrintEvent("operator-resolve-unknown-0001",1L);
        long printer=readyPrinter(1L,"4000000991");
        var worker=newWorker(); worker.processPendingEvents();
        long job=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        String token="expired-operator-lease-0001";
        jdbc.update("UPDATE yshop_order_print_job SET state='SENDING',attempts=1,lease_token=?,lease_expires_at=? WHERE id=?",
                token,LocalDateTime.now().minusMinutes(2),job);
        jdbc.update("INSERT INTO yshop_order_print_device_claim(tenant_id,printer_id,job_id,lease_token,shop_id) VALUES(1,?,?,?,1)",
                printer,job,token);
        assertEquals(1,worker.recoverExpiredJobs());
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        assertEquals("PRINT_RECOVERY_CONFIRMATION_REQUIRED",assertThrows(PrinterFailure.class,()->jobs.resolveUnknown(
                new OrderPrintJobService.ResolveUnknownRequest(job,"resolve-unknown-confirm-01","已检查",false,true))).getMessage());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE printer_id=?",Integer.class,printer));
        assertEquals("PRINT_RECOVERY_REQUIRED",assertThrows(PrinterFailure.class,()->jobs.reprint(
                new OrderPrintJobService.ReprintRequest(job,"blocked-reprint-request-01","结果待核对后重打"))).getMessage());

        var request=new OrderPrintJobService.ResolveUnknownRequest(job,"resolve-unknown-confirm-01",
                "已确认旧发送进程停止且打印服务确认未出纸",true,true);
        var resolved=jobs.resolveUnknown(request);
        var replay=jobs.resolveUnknown(request);
        assertEquals("FAILED",resolved.state());
        assertEquals("OPERATOR_CONFIRMED_NOT_PRINTED",resolved.errorCode());
        assertEquals(resolved.id(),replay.id());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE printer_id=?",Integer.class,printer));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim_resolution WHERE tenant_id=1 AND job_id=?",Integer.class,job));
        assertEquals(1L,jdbc.queryForObject("SELECT actor_id FROM yshop_order_print_device_claim_resolution WHERE job_id=?",Long.class,job));
        assertEquals("NOT_PRINTED_CONFIRMED_CLAIM_RELEASED",jdbc.queryForObject("SELECT outcome FROM yshop_order_print_device_claim_resolution WHERE job_id=?",String.class,job));
        assertEquals("PRINT_RECOVERY_CONFIRMATION_REQUIRED",assertThrows(PrinterFailure.class,()->jobs.resolveUnknown(
                new OrderPrintJobService.ResolveUnknownRequest(job,"resolve-no-outcome-0001","尚未核验结果",true,false,false))).getMessage());
        assertEquals("PRINT_RECOVERY_CONFIRMATION_REQUIRED",assertThrows(PrinterFailure.class,()->jobs.resolveUnknown(
                new OrderPrintJobService.ResolveUnknownRequest(job,"resolve-two-outcomes-001","结果不能同时成立",true,true,true))).getMessage());
        assertEquals("REQUEST_CONFLICT",assertThrows(PrinterFailure.class,()->jobs.resolveUnknown(
                new OrderPrintJobService.ResolveUnknownRequest(job,"resolve-unknown-confirm-01","不同操作说明",true,true))).getMessage());

        jobs.reprint(new OrderPrintJobService.ReprintRequest(job,"operator-reprint-child-0001","核对未出纸后重打"));
        worker.dispatchPendingJobs();
        assertEquals(1,fake.printCalls.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_job WHERE parent_job_id=?",Integer.class,job));
    }

    @Test void confirmedPrintedUnknownJobIsAuditedAndReleasedWithoutResendOrReprint() throws Exception {
        long event=insertPrintEvent("operator-confirmed-printed-0001",1L);
        long printer=readyPrinter(1L,"4000000989");
        var worker=newWorker(); worker.processPendingEvents();
        long job=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        fake.crash=true;
        assertThrows(IllegalStateException.class,worker::dispatchPendingJobs);
        assertEquals(1,fake.printCalls.get());
        jdbc.update("UPDATE yshop_order_print_job SET lease_expires_at=? WHERE id=?",LocalDateTime.now().minusMinutes(2),job);
        assertEquals(1,worker.recoverExpiredJobs());
        fake.crash=false;

        var request=new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"jobId\":"+job+",\"requestKey\":\"printed-recovery-request-01\","
                        +"\"reason\":\"设备端确认已出纸且旧发送进程已停止\",\"senderStopped\":true,"
                        +"\"notPrintedConfirmed\":false,\"printedConfirmed\":true}",
                OrderPrintJobService.ResolveUnknownRequest.class);
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        var resolved=jobs.resolveUnknown(request);
        var replay=jobs.resolveUnknown(request);

        assertEquals("ACCEPTED",resolved.state());
        assertEquals("OPERATOR_CONFIRMED_PRINTED",resolved.errorCode());
        assertEquals(resolved.id(),replay.id());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE printer_id=?",Integer.class,printer));
        assertEquals("PRINTED_CONFIRMED_CLAIM_RELEASED",jdbc.queryForObject(
                "SELECT outcome FROM yshop_order_print_device_claim_resolution WHERE tenant_id=1 AND job_id=?",String.class,job));
        assertEquals(1L,jdbc.queryForObject("SELECT actor_id FROM yshop_order_print_device_claim_resolution WHERE job_id=?",Long.class,job));
        assertEquals("REQUEST_CONFLICT",assertThrows(PrinterFailure.class,()->jobs.resolveUnknown(
                new OrderPrintJobService.ResolveUnknownRequest(job,"printed-recovery-request-01",
                        "同一请求编号不能改为未出纸",true,true,false))).getMessage());
        assertEquals("PRINT_ALREADY_CONFIRMED",assertThrows(PrinterFailure.class,()->jobs.reprint(
                new OrderPrintJobService.ReprintRequest(job,"printed-recovery-reprint-01","结果已核验出纸"))).getMessage());
        worker.dispatchPendingJobs();
        assertEquals(1,fake.printCalls.get());
    }

    @Test void printerReboundDuringSessionPreparationCannotReceiveOldShopOrder() throws Exception {
        long event=insertPrintEvent("rebind-during-prepare-0001",1L);
        long printer=readyPrinter(1L,"4000000998");
        var worker=newWorker(); worker.processPendingEvents();
        CountDownLatch preparing=new CountDownLatch(1),resume=new CountDownLatch(1);
        fake.preparation=()->{
            if(fake.sessionCalls.get()==1) {
                preparing.countDown();
                try {
                    if(!resume.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("PREPARATION_RESUME_TIMEOUT");
                } catch(InterruptedException failure) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
                }
            }
        };
        var pool=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> dispatch=pool.submit(worker::dispatchPendingJobs);
            assertTrue(preparing.await(5,TimeUnit.SECONDS),"worker did not reach provider session preparation");

            service.unbind(printer);
            service.bind(new BindingRequest(printer,2L));
            resume.countDown();

            assertEquals(0,dispatch.get(10,TimeUnit.SECONDS));
            assertEquals(0,fake.printCalls.get(),"stale shop-1 job must not be sent to the shop-2 device");
            assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",String.class,event));
            assertEquals("PRINTER_NOT_BOUND",jdbc.queryForObject("SELECT last_error FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",String.class,event));
            assertEquals(2L,service.get(printer).shopId());
        } finally {
            resume.countDown(); pool.shutdownNow(); pool.awaitTermination(5,TimeUnit.SECONDS);
        }
    }

    @Test void printerDisabledDuringSessionPreparationCannotReceiveOrder() throws Exception {
        long event=insertPrintEvent("disable-during-prepare-0001",1L);
        long printer=readyPrinter(1L,"4000000999");
        var worker=newWorker(); worker.processPendingEvents();
        CountDownLatch preparing=new CountDownLatch(1),resume=new CountDownLatch(1);
        fake.preparation=()->{
            if(fake.sessionCalls.get()==1) {
                preparing.countDown();
                try { if(!resume.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("PREPARATION_RESUME_TIMEOUT"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        };
        var pool=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> dispatch=pool.submit(worker::dispatchPendingJobs);
            assertTrue(preparing.await(5,TimeUnit.SECONDS));
            service.save(new SaveRequest(printer,"测试设备","YILIAN_YUN","4000000999","K4","CLOUD",false,Map.of()),true);
            resume.countDown();
            assertEquals(0,dispatch.get(10,TimeUnit.SECONDS));
            assertEquals(0,fake.printCalls.get());
            assertEquals("PRINTER_DISABLED",jdbc.queryForObject("SELECT last_error FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",String.class,event));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim",Integer.class));
        } finally {
            resume.countDown(); pool.shutdownNow(); pool.awaitTermination(5,TimeUnit.SECONDS);
        }
    }

    @Test void authorizationRevokedDuringSessionPreparationCannotReceiveOrder() throws Exception {
        long event=insertPrintEvent("revoke-during-prepare-0001",1L);
        long printer=readyPrinter(1L,"4000001000");
        var worker=newWorker(); worker.processPendingEvents();
        CountDownLatch preparing=new CountDownLatch(1),resume=new CountDownLatch(1);
        fake.preparation=()->{
            if(fake.sessionCalls.get()==1) {
                preparing.countDown();
                try { if(!resume.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("PREPARATION_RESUME_TIMEOUT"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        };
        var pool=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> dispatch=pool.submit(worker::dispatchPendingJobs);
            assertTrue(preparing.await(5,TimeUnit.SECONDS));
            service.unbind(printer);
            assertEquals(Authorization.UNAUTHORIZED,service.authorize(printer,true).authorization());
            service.bind(new BindingRequest(printer,1L));
            resume.countDown();
            assertEquals(0,dispatch.get(10,TimeUnit.SECONDS));
            assertEquals(0,fake.printCalls.get());
            assertEquals("PRINTER_NOT_AUTHORIZED",jdbc.queryForObject("SELECT last_error FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",String.class,event));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim",Integer.class));
        } finally {
            resume.countDown(); pool.shutdownNow(); pool.awaitTermination(5,TimeUnit.SECONDS);
        }
    }

    @Test void twoWorkersCannotPrintOrRebindWhileOneDeviceSendIsInFlight() throws Exception {
        long firstEvent=insertPrintEvent("two-workers-first-event-01",1L);
        long secondEvent=insertPrintEvent("two-workers-second-event-01",1L);
        long printer=readyPrinter(1L,"4000001001");
        var firstWorker=newWorker(); var secondWorker=newWorker(); firstWorker.processPendingEvents();
        CountDownLatch printing=new CountDownLatch(1),resume=new CountDownLatch(1);
        fake.beforePrint=()->{
            if(fake.printCalls.get()==1) {
                printing.countDown();
                try { if(!resume.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("PRINT_RESUME_TIMEOUT"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        };
        var pool=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first=pool.submit(firstWorker::dispatchPendingJobs);
            assertTrue(printing.await(5,TimeUnit.SECONDS),"first worker did not enter provider print");
            assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->service.unbind(printer)).getMessage());
            assertEquals(0,secondWorker.dispatchPendingJobs());
            assertEquals(1,fake.printCalls.get(),"second worker must not enter the provider while the device is reserved");
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE printer_id=?",Integer.class,printer));
            resume.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS));
            assertEquals("ACCEPTED",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE event_id=?",String.class,firstEvent));
            assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE event_id=?",String.class,secondEvent));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim",Integer.class));
        } finally {
            resume.countDown(); pool.shutdownNow(); pool.awaitTermination(5,TimeUnit.SECONDS);
        }
    }

    @Test void reprintCreatesAuditedChild() {
        long event=insertPrintEvent("reprint-event-0001",1L); readyPrinter(1L,"4000000993");
        var worker=newWorker(); worker.processPendingEvents(); worker.dispatchPendingJobs();
        long parent=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,parent));
        assertTrue(fake.receipts.get(0).lines().contains("联系电话：138****1234"));
        assertTrue(fake.receipts.get(0).lines().contains("Synthetic tea x2"));
        assertTrue(fake.receipts.get(0).lines().contains("规格：large|less ice"));
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        login(2,0);
        assertEquals("PRINT_JOB_NOT_FOUND",assertThrows(PrinterFailure.class,()->jobs.reprint(
                new OrderPrintJobService.ReprintRequest(parent,"foreign-tenant-reprint-01","跨租户重打"))).getMessage());
        login(1,0);
        var child=jobs.reprint(new OrderPrintJobService.ReprintRequest(parent,"manual-reprint-0001","顾客反馈未收到小票"));
        var replay=jobs.reprint(new OrderPrintJobService.ReprintRequest(parent,"manual-reprint-0001","顾客反馈未收到小票"));
        assertEquals(child.id(),replay.id());
        assertNotEquals(parent,child.id());
        assertEquals(parent,jdbc.queryForObject("SELECT parent_job_id FROM yshop_order_print_job WHERE id=?",Long.class,child.id()));
        assertEquals("顾客反馈未收到小票",jdbc.queryForObject("SELECT reprint_reason FROM yshop_order_print_job WHERE id=?",String.class,child.id()));
        assertEquals("REPRINT",jdbc.queryForObject("SELECT purpose FROM yshop_order_print_job WHERE id=?",String.class,child.id()));
        assertEquals(jdbc.queryForObject("SELECT payload_snapshot FROM yshop_order_print_job WHERE id=?",String.class,parent),
                jdbc.queryForObject("SELECT payload_snapshot FROM yshop_order_print_job WHERE id=?",String.class,child.id()));
        assertEquals("REQUEST_CONFLICT",assertThrows(PrinterFailure.class,()->jobs.reprint(
                new OrderPrintJobService.ReprintRequest(parent,"manual-reprint-0001","不同重打原因"))).getMessage());
        assertEquals(1,fake.printCalls.get());
        worker.dispatchPendingJobs();
        assertEquals(2,fake.printCalls.get());
        assertEquals(fake.receipts.get(0),fake.receipts.get(1));
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE id=?",String.class,child.id()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Integer.class,event));
    }

    @Test void receiptAndReprintIncludeFrozenCatalogCustomizations() {
        long event=insertPrintEvent("customized-receipt-event-0001",1L);
        String cartDetails=JsonUtils.toJsonString(Map.of("version",1,"options",List.of(
                Map.of("groupName","甜度","name","五分糖","quantity",1,"surcharge",new java.math.BigDecimal("0.00")),
                Map.of("groupName","加料","name","珍珠","quantity",2,"surcharge",new java.math.BigDecimal("1.50")))));
        String payload=JsonUtils.toJsonString(Map.of(
                "schemaVersion",1,"orderId",123L,"orderCode","synthetic-customized-order",
                "shopName","Synthetic frozen shop","pickupNumber",123,"contactPhone","138****1234",
                "orderCreatedAt","2031-10-22T10:00:00","totalQuantity",2,"paidAmount",new java.math.BigDecimal("19.50"),
                "items",List.of(Map.of("title","Synthetic tea","quantity",2,"unitPrice",new java.math.BigDecimal("9.75"),
                        "specification","large|less ice","cartDetails",cartDetails))));
        jdbc.update("UPDATE yshop_order_fulfillment_print_event SET payload_snapshot=? WHERE id=?",payload,event);
        readyPrinter(1L,"4000000997");
        var worker=newWorker(); worker.processPendingEvents(); worker.dispatchPendingJobs();
        assertEquals(1,fake.receipts.size());
        assertTrue(fake.receipts.get(0).lines().contains("定制：甜度 五分糖"));
        assertTrue(fake.receipts.get(0).lines().contains("定制：加料 珍珠 x2（+￥3.00）"));

        long parent=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        jobs.reprint(new OrderPrintJobService.ReprintRequest(parent,"customized-reprint-0001","顾客反馈未收到小票"));
        worker.dispatchPendingJobs();
        assertEquals(2,fake.receipts.size());
        assertEquals(fake.receipts.get(0),fake.receipts.get(1));
        assertTrue(fake.receipts.get(1).lines().contains("定制：甜度 五分糖"));
        assertTrue(fake.receipts.get(1).lines().contains("定制：加料 珍珠 x2（+￥3.00）"));
    }

    @Test void staleReprintRetryReadsConcurrentCommittedTask() throws Exception {
        long event=insertPrintEvent("reprint-stale-event-0001",1L); readyPrinter(1L,"4000000995");
        var worker=newWorker(); worker.processPendingEvents(); worker.dispatchPendingJobs();
        long parent=jdbc.queryForObject("SELECT id FROM yshop_order_print_job WHERE tenant_id=1 AND event_id=?",Long.class,event);
        var jobs=new OrderPrintJobService(jdbc,manager,service,worker);
        var request=new OrderPrintJobService.ReprintRequest(parent,"concurrent-reprint-key-01","结果待核对后确认重打");
        var snapshotEstablished=new CountDownLatch(1); var concurrentCommitted=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var staleReplay=pool.submit(()->{
                login(1,0);
                var snapshot=new TransactionTemplate(manager);
                snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return snapshot.execute(status->{
                    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_job WHERE tenant_id=1 AND request_key=?",Integer.class,request.requestKey()));
                    snapshotEstablished.countDown();
                    try { assertTrue(concurrentCommitted.await(5,TimeUnit.SECONDS)); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    return jobs.reprint(request).id();
                });
            });
            assertTrue(snapshotEstablished.await(5,TimeUnit.SECONDS));
            login(1,0);
            long committed=jobs.reprint(request).id();
            concurrentCommitted.countDown();
            assertEquals(committed,staleReplay.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_job WHERE tenant_id=1 AND request_key=?",Integer.class,request.requestKey()));
        } finally {
            concurrentCommitted.countDown();
            pool.shutdownNow();
            cleanup();
        }
    }

    @Test void shopDeleteCurrentReadSeesClaimCommittedAfterRepeatableReadSnapshot() throws Exception {
        long printer=readyPrinter(1L,"4000000994");
        var snapshotEstablished=new CountDownLatch(1);
        var claimCommitted=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var deletion=pool.submit(()->{
                login(1,0);
                var transaction=new TransactionTemplate(manager);
                transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                assertThrows(PrinterFailure.class,()->transaction.executeWithoutResult(status->{
                    assertTrue(repo.lockShop(1));
                    assertEquals(1L,jdbc.queryForObject("SELECT id FROM yshop_store_shop WHERE id=1",Long.class));
                    snapshotEstablished.countDown();
                    try { assertTrue(claimCommitted.await(5,TimeUnit.SECONDS)); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    jdbc.update("UPDATE yshop_store_shop SET deleted=1 WHERE id=1");
                    repo.shopDeleted(1);
                }));
            });
            assertTrue(snapshotEstablished.await(5,TimeUnit.SECONDS));
            login(1,0);
            new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM yshop_printer WHERE id=? FOR UPDATE",Long.class,printer);
                repo.claimOrderPrintDevice(repo.require(1,printer,false),9001,"lease-delete-race",1L);
            });
            claimCommitted.countDown();
            deletion.get(10,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_binding WHERE printer_id=? AND shop_id=1",Integer.class,printer));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_device_claim WHERE printer_id=?",Integer.class,printer));
        } finally {
            claimCommitted.countDown();
            pool.shutdownNow();
        }
    }
    @Test void secretsAreEncryptedWriteOnlyAndPreservedOnBlankEdit() {
        long id=device("4000000001");
        String encrypted=jdbc.queryForObject("SELECT encrypted_config FROM yshop_printer WHERE id=?",String.class,id);
        assertFalse(encrypted.contains("synthetic-device-key")); assertTrue(encrypted.startsWith("v1:"));
        assertFalse(JsonUtils.toJsonString(service.get(id)).contains("synthetic-device-key"));
        assertEquals(Set.of("terminalKey"),service.get(id).configuredFields());
        service.save(request(id,"4000000001",Map.of("terminalKey","")),true);
        assertEquals(encrypted,jdbc.queryForObject("SELECT encrypted_config FROM yshop_printer WHERE id=?",String.class,id));
        String another=cipher.encrypt("same","context"); assertNotEquals(another,cipher.encrypt("same","context"));
        assertThrows(PrinterFailure.class,()->cipher.decrypt(another,"wrong-context"));
    }
    @Test void localManagementWorksWithoutServiceOrEncryptionConfiguration() {
        PrinterService local=new PrinterService(repo,new PrinterProviderRegistry(List.of(fake)),new PrinterSecretCipher(""),new TransactionTemplate(manager),printerAccess,false);
        long id=local.save(request(null,"4000000001",Map.of()),false);
        assertTrue(local.get(id).configuredFields().isEmpty());
        local.bind(new BindingRequest(id,1L));
        assertEquals(1L,local.get(id).shopId());
        assertEquals("ENCRYPTION_NOT_CONFIGURED",assertThrows(PrinterFailure.class,()->local.save(request(id,"4000000001",Map.of("terminalKey","synthetic")),true)).getMessage());
    }
    @Test void duplicateRestoreAndImmutableIdentity() {
        long id=device("4000000001");
        assertEquals("DUPLICATE_DEVICE",assertThrows(PrinterFailure.class,()->device("4000000001")).getMessage());
        assertEquals("IDENTITY_IMMUTABLE",assertThrows(PrinterFailure.class,()->service.save(request(id,"4000000002",Map.of()),true)).getMessage());
        service.delete(id); assertEquals(id,device("4000000001"));
    }
    @Test void oneStoreHasMultipleDevicesButDeviceCannotBeShared() {
        long one=device("4000000001"),two=device("4000000002");
        service.bind(new BindingRequest(one,1L)); service.bind(new BindingRequest(two,1L));
        assertEquals(2L,service.page(1,10,null,1L).getTotal());
        assertEquals("UNBIND_FIRST",assertThrows(PrinterFailure.class,()->service.bind(new BindingRequest(one,2L))).getMessage());
        assertThrows(PrinterFailure.class,()->service.delete(one));
        service.unbind(one); service.bind(new BindingRequest(one,2L)); assertEquals(2L,service.get(one).shopId());
        new TransactionTemplate(manager).executeWithoutResult(tx->{ repo.lockShop(2); jdbc.update("UPDATE yshop_store_shop SET deleted=1 WHERE id=2"); repo.shopDeleted(2); });
        assertNull(service.get(one).shopId()); assertThrows(PrinterFailure.class,()->service.bind(new BindingRequest(one,2L)));
        jdbc.update("UPDATE yshop_store_shop SET deleted=0 WHERE id=2");
    }
    @Test void tenantAndHeadquartersChecksApplyToDirectServiceCalls() {
        long id=device("4000000001"); login(2,0);
        assertEquals(0L,service.page(1,10,null,null).getTotal()); assertThrows(PrinterFailure.class,()->service.get(id));
        headquartersRoles.clear(); login(1,1); assertThrows(PrinterFailure.class,service::providers); assertThrows(PrinterFailure.class,()->service.bind(new BindingRequest(id,1L)));
        headquartersRoles.add("business_headquarters"); login(1,0); TenantContextHolder.clear(); assertEquals(id,service.get(id).id());
        TenantContextHolder.setTenantId(2L); assertThrows(PrinterFailure.class,()->service.get(id));
    }
    @Test void singleTenantNullTokenAndDatabaseDefaultShareScopeButTenantModeRejectsMissingIdentity() {
        login(1,0); TenantContextHolder.clear();
        var user=(LoginUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        printerAdminTenants.put(1L,0L);
        user.setTenantId(null);
        long id=device("4000000001");
        assertEquals(0L,jdbc.queryForObject("SELECT tenant_id FROM yshop_printer WHERE id=?",Long.class,id));
        user.setTenantId(0L); assertEquals(id,service.get(id).id());
        user.setTenantId(null);
        fake.crash=true;
        assertThrows(IllegalStateException.class,()->service.authorize(id,false));
        fake.crash=false;
        PendingOperation pending=service.pendingOperation(id);
        assertEquals("AUTHORIZE",pending.operationType());
        assertEquals("AUTHORIZATION_UNKNOWN",service.resolveOperation(new ResolveOperationRequest(
                id,pending.operationToken(),"单租户操作的服务端身份已核验",true,true)).outcome());
        assertNull(service.pendingOperation(id));
        service.bind(new BindingRequest(id,1L));
        assertEquals(1L,service.get(id).shopId());

        PrinterRepository observedRepo=spy(repo);
        PrinterService strict=new PrinterService(observedRepo,new PrinterProviderRegistry(List.of(fake)),cipher,new TransactionTemplate(manager),printerAccess,true);
        clearInvocations(printerPermissions,observedRepo);
        assertThrows(PrinterFailure.class,()->strict.get(id));
        verifyNoInteractions(printerPermissions,observedRepo);
        user.setTenantId(0L); TenantContextHolder.setTenantId(0L);
        assertEquals(id,strict.get(id).id());
        fake.deviceState=DeviceState.UNKNOWN;
        fake.crash=true;
        assertThrows(IllegalStateException.class,()->strict.authorize(id,false));
        fake.crash=false;
        fake.deviceState=DeviceState.ONLINE;
        PendingOperation strictPending=strict.pendingOperation(id);
        assertEquals("AUTHORIZE",strictPending.operationType());
        clearInvocations(observedRepo);
        clearInvocations(printerPermissions,observedRepo);
        TenantContextHolder.setTenantId(1L);
        assertThrows(PrinterFailure.class,()->strict.pendingOperation(id));
        assertThrows(PrinterFailure.class,()->strict.resolveOperation(new ResolveOperationRequest(
                id,strictPending.operationToken(),"租户令牌与上下文不一致时拒绝恢复",true,true)));
        verifyNoInteractions(printerPermissions,observedRepo);

        login(1,1);
        long foreignId=device("4000000002");
        login(0,0);
        assertThrows(PrinterFailure.class,()->strict.get(foreignId));
        TenantContextHolder.setTenantId(1L); assertThrows(PrinterFailure.class,()->service.get(id));
        TenantContextHolder.clear(); headquartersRoles.clear(); user.setShopId(1L); assertThrows(PrinterFailure.class,()->service.get(id));
    }
    @Configuration @EnableTransactionManagement
    static class ShopTransactions {}
    private StoreShopMapper shopMapper() {
        StoreShopMapper mapper=mock(StoreShopMapper.class);
        when(mapper.selectById(anyLong())).thenAnswer(call->{
            long id=call.getArgument(0);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM yshop_store_shop WHERE id=? AND deleted=0",Long.class,id)==0) return null;
            StoreShopDO shop=new StoreShopDO(); shop.setId(id); return shop;
        });
        when(mapper.deleteById(anyLong())).thenAnswer(call->jdbc.update("UPDATE yshop_store_shop SET deleted=1 WHERE id=? AND deleted=0",(Object)call.getArgument(0)));
        return mapper;
    }
    private AnnotationConfigApplicationContext shops(StoreShopMapper mapper,PrinterRepository repository) {
        var context=new AnnotationConfigApplicationContext();
        PermissionApi permissions=mock(PermissionApi.class);
        when(permissions.hasAnyRoles(anyLong(),any(String[].class))).thenAnswer(call->{
            var user=(LoginUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            return user.getShopId()!=null&&user.getShopId()==0;
        });
        context.registerBean(PlatformTransactionManager.class,()->manager);
        context.registerBean(PrinterRepository.class,()->repository);
        context.registerBean(StoreShopMapper.class,()->mapper);
        context.registerBean(StoreAccessService.class,()->new StoreAccessService(jdbc,permissions,mock(AdminUserApi.class)));
        context.register(ShopTransactions.class,StoreShopServiceImpl.class); context.refresh();
        return context;
    }
    @Test void realShopDeletionClearsBindingsAndRollsBackOnFailure() {
        long id=device("4000000001"); service.bind(new BindingRequest(id,1L));
        StoreShopMapper mapper=shopMapper();
        try(var context=shops(mapper,repo)) {
            StoreShopService shops=context.getBean(StoreShopService.class);
            doAnswer(call->{jdbc.update("UPDATE yshop_store_shop SET deleted=1 WHERE id=1");throw new IllegalStateException("synthetic database failure");})
                    .when(mapper).deleteById(1L);
            assertThrows(IllegalStateException.class,()->shops.deleteShop(1L));
            assertEquals(1L,service.get(id).shopId()); assertTrue(repo.lockShop(1));
            doAnswer(call->jdbc.update("UPDATE yshop_store_shop SET deleted=1 WHERE id=1 AND deleted=0")).when(mapper).deleteById(1L);
            shops.deleteShop(1L); assertNull(service.get(id).shopId()); assertFalse(repo.lockShop(1));
        }
    }
    @Test void unauthorizedShopDeletionIsDeniedBeforeLookingUpAnyShop() {
        long id=device("4000000001"); service.bind(new BindingRequest(id,1L));
        try(var context=shops(shopMapper(),repo)) {
            StoreShopService shops=context.getBean(StoreShopService.class);
            login(1,1);
            assertThrows(AccessDeniedException.class,()->shops.deleteShop(1L));
            assertThrows(AccessDeniedException.class,()->shops.deleteShop(999L));
            login(1,0); assertEquals(1L,service.get(id).shopId()); assertTrue(repo.lockShop(1));
        }
    }
    @Test void zeroAffectedShopDeletionDoesNotClearBindings() {
        long id=device("4000000001"); service.bind(new BindingRequest(id,1L));
        StoreShopMapper mapper=shopMapper(); doReturn(0).when(mapper).deleteById(1L);
        try(var context=shops(mapper,repo)) {
            assertThrows(RuntimeException.class,()->context.getBean(StoreShopService.class).deleteShop(1L));
            assertEquals(1L,service.get(id).shopId()); assertTrue(repo.lockShop(1));
        }
    }
    @Test void bindingCleanupFailureRollsBackTheShopDeletionAndBindingRemoval() {
        long id=device("4000000001"); service.bind(new BindingRequest(id,1L));
        var failing=new PrinterRepository(jdbc) {
            @Override public void shopDeleted(long shop) { super.shopDeleted(shop); throw new IllegalStateException("SYNTHETIC_CLEANUP_FAILURE"); }
        };
        try(var context=shops(shopMapper(),failing)) {
            assertThrows(IllegalStateException.class,()->context.getBean(StoreShopService.class).deleteShop(1L));
            assertTrue(repo.lockShop(1)); assertEquals(1L,service.get(id).shopId());
        }
    }
    @Test void zeroAffectedBindingCleanupRollsBackShopDeletion() {
        long id=device("4000000001"); service.bind(new BindingRequest(id,1L));
        JdbcTemplate failed=spy(jdbc);
        doReturn(0).when(failed).update("DELETE FROM yshop_printer_binding WHERE shop_id=?",1L);
        try(var context=shops(shopMapper(),new PrinterRepository(failed))) {
            assertThrows(IllegalStateException.class,()->context.getBean(StoreShopService.class).deleteShop(1L));
            assertTrue(repo.lockShop(1)); assertEquals(1L,service.get(id).shopId());
        }
    }
    @Test void deletionHoldingShopLockRejectsAConcurrentBinding() throws Exception {
        long id=device("4000000001"); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        StoreShopMapper mapper=shopMapper();
        doAnswer(call->{ entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); return jdbc.update("UPDATE yshop_store_shop SET deleted=1 WHERE id=1 AND deleted=0"); }).when(mapper).deleteById(1L);
        var workers=Executors.newFixedThreadPool(2);
        try(var context=shops(mapper,repo)) {
            var deleting=workers.submit(()->{login(1,0);try{context.getBean(StoreShopService.class).deleteShop(1L);}finally{cleanup();}});
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            var binding=workers.submit(()->{login(1,0);try{return assertThrows(PrinterFailure.class,()->service.bind(new BindingRequest(id,1L))).getMessage();}finally{cleanup();}});
            release.countDown(); deleting.get(15,TimeUnit.SECONDS);
            assertEquals("SHOP_NOT_FOUND",binding.get(15,TimeUnit.SECONDS)); assertNull(service.get(id).shopId());
        } finally {release.countDown();workers.shutdownNow();}
    }
    @Test void bindingHoldingShopLockIsCleanedByConcurrentDeletion() throws Exception {
        long id=device("4000000001"); var bound=new CountDownLatch(1); var release=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try(var context=shops(shopMapper(),repo)) {
            var binding=workers.submit(()->{login(1,0);try{new TransactionTemplate(manager).executeWithoutResult(tx->{
                service.bind(new BindingRequest(id,1L)); bound.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new RuntimeException(ex);}
            });}finally{cleanup();}});
            assertTrue(bound.await(10,TimeUnit.SECONDS));
            var deleting=workers.submit(()->{login(1,0);try{context.getBean(StoreShopService.class).deleteShop(1L);}finally{cleanup();}});
            release.countDown(); binding.get(15,TimeUnit.SECONDS); deleting.get(15,TimeUnit.SECONDS);
            assertNull(service.get(id).shopId()); assertFalse(repo.lockShop(1));
        } finally {release.countDown();workers.shutdownNow();}
    }
    @Test void authorizationUnknownSurvivesFailureAndBlocksDelete() {
        long id=device("4000000001"); fake.authorizeFailure=new PrinterFailure("PROVIDER_TIMEOUT",true);
        assertEquals(Authorization.UNKNOWN,service.authorize(id,false).authorization());
        assertThrows(PrinterFailure.class,()->service.delete(id));
        fake.authorizeFailure=null; assertEquals(Authorization.AUTHORIZED,service.authorize(id,false).authorization());
        service.bind(new BindingRequest(id,1L)); assertThrows(PrinterFailure.class,()->service.authorize(id,true));
        service.unbind(id); service.authorize(id,true); service.delete(id);
    }
    @Test void authorizationCrashIntentRequiresAuditedTokenBoundRecovery() {
        long id=device("4000000001"); fake.crash=true;
        assertThrows(IllegalStateException.class,()->service.authorize(id,false));
        assertEquals(Authorization.UNKNOWN,service.get(id).authorization());
        assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->service.authorize(id,false)).getMessage());
        assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->service.bind(new BindingRequest(id,1L))).getMessage());
        jdbc.update("UPDATE yshop_printer SET authorization_operation_at=? WHERE id=?",java.time.LocalDateTime.now().minusMinutes(2),id);
        PendingOperation pending=service.pendingOperation(id);
        assertEquals("AUTHORIZE",pending.operationType());
        assertThrows(PrinterFailure.class,()->service.resolveOperation(new ResolveOperationRequest(id,pending.operationToken(),"核实设备状态",false,true)));
        assertEquals(pending.operationToken(),service.pendingOperation(id).operationToken());
        assertThrows(PrinterFailure.class,()->service.resolveOperation(new ResolveOperationRequest(id,UUID.randomUUID().toString(),"核实设备状态",true,true)));
        var resolved=service.resolveOperation(new ResolveOperationRequest(id,pending.operationToken(),"厂商后台确认授权请求已结束",true,true));
        assertEquals("AUTHORIZATION_UNKNOWN",resolved.outcome());
        assertNull(service.pendingOperation(id));
        assertEquals(Authorization.UNKNOWN,service.get(id).authorization());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_operation_resolution WHERE tenant_id=1 AND printer_id=? AND operation_token=?",Integer.class,id,pending.operationToken()));
        assertEquals("PRINTER_OPERATION_TOKEN_MISMATCH",assertThrows(PrinterFailure.class,()->service.resolveOperation(
                new ResolveOperationRequest(id,pending.operationToken(),"重复恢复不能再写审计",true,true))).getMessage());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_operation_resolution WHERE tenant_id=1 AND printer_id=? AND operation_token=?",Integer.class,id,pending.operationToken()));
        fake.crash=false; assertEquals(Authorization.AUTHORIZED,service.authorize(id,false).authorization());
        fake.crash=true;
        assertThrows(IllegalStateException.class,()->service.authorize(id,true));
        PendingOperation deauthorization=service.pendingOperation(id);
        assertEquals("DEAUTHORIZE",deauthorization.operationType());
        fake.crash=false;
        service.resolveOperation(new ResolveOperationRequest(id,deauthorization.operationToken(),"已核实撤权调用已结束",true,true));
        assertEquals("AUTHORIZED",jdbc.queryForObject("SELECT prior_authorization FROM yshop_printer_operation_resolution WHERE tenant_id=1 AND printer_id=? AND operation_token=?",
                String.class,id,deauthorization.operationToken()));
    }
    @Test void activeProviderCallRemainsBusyAfterIntentTimestampExpires() throws Exception {
        long id=device("4000000001"); service.bind(new BindingRequest(id,1L));
        CountDownLatch enteredStatus=new CountDownLatch(1),resumeStatus=new CountDownLatch(1);
        fake.beforeStatus=()->{
            enteredStatus.countDown();
            try { if(!resumeStatus.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("STATUS_RESUME_TIMEOUT"); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        };
        var executor=Executors.newSingleThreadExecutor();
        try {
            Future<StatusResult> refresh=executor.submit(()->{login(1,0);try{return service.status(id);}finally{cleanup();}});
            assertTrue(enteredStatus.await(5,TimeUnit.SECONDS));
            jdbc.update("UPDATE yshop_printer SET authorization_operation_at=? WHERE id=?",java.time.LocalDateTime.now().minusMinutes(2),id);

            login(1,0);
            assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,()->service.unbind(id)).getMessage());
            try(var context=shops(shopMapper(),repo)) {
                assertEquals("PRINTER_BUSY",assertThrows(PrinterFailure.class,
                        ()->context.getBean(StoreShopService.class).deleteShop(1L)).getMessage());
            }
            assertEquals(1L,service.get(id).shopId());

            resumeStatus.countDown();
            assertEquals(DeviceState.ONLINE,refresh.get(10,TimeUnit.SECONDS).state());
        } finally {
            resumeStatus.countDown(); executor.shutdownNow(); executor.awaitTermination(5,TimeUnit.SECONDS); cleanup();
        }
    }
    @Test void crashedStatusRefreshIsAuditedAndLeavesCachedStatusUnknown() {
        long id=device("4000000001");
        jdbc.update("UPDATE yshop_printer SET device_state='ONLINE',state_checked_at=CURRENT_TIMESTAMP WHERE id=?",id);
        fake.preparation=()->{throw new IllegalStateException("SIMULATED_CRASH");};
        assertThrows(IllegalStateException.class,()->service.status(id));
        PendingOperation pending=service.pendingOperation(id);
        assertEquals("STATUS_REFRESH",pending.operationType());
        fake.preparation=()->{};
        var resolved=service.resolveOperation(new ResolveOperationRequest(id,pending.operationToken(),"确认状态查询请求已结束",true,true));
        assertEquals("DEVICE_STATUS_UNKNOWN",resolved.outcome());
        assertEquals(DeviceState.UNKNOWN,service.get(id).deviceState());
        assertEquals(Authorization.UNAUTHORIZED,service.get(id).authorization());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_operation_resolution WHERE tenant_id=1 AND printer_id=? AND operation_type='STATUS_REFRESH'",Integer.class,id));
    }
    @Test void legacyOperationWithoutNewTypeMetadataRecoversConservatively() {
        long id=device("4000000001");
        service.authorize(id,false);
        String token=UUID.randomUUID().toString();
        jdbc.update("UPDATE yshop_printer SET authorization_operation=?,authorization_operation_at=CURRENT_TIMESTAMP,"
                +"authorization_operation_type=NULL,authorization_operation_request_id=NULL WHERE tenant_id=1 AND id=?",token,id);
        PendingOperation pending=service.pendingOperation(id);
        assertEquals("LEGACY_PROVIDER_OPERATION",pending.operationType());
        var resolved=service.resolveOperation(new ResolveOperationRequest(id,token,"确认历史厂商操作已结束",true,true));
        assertEquals("PROVIDER_RESULT_UNKNOWN",resolved.outcome());
        assertEquals(Authorization.UNKNOWN,service.get(id).authorization());
        assertEquals(DeviceState.UNKNOWN,service.get(id).deviceState());
    }
    @Test void statusFailuresAreUnknownInsteadOfOffline() {
        long id=device("4000000001"); fake.deviceState=DeviceState.PAPER_OUT; assertEquals(DeviceState.PAPER_OUT,service.status(id).state());
        fake.statusFailure=new PrinterFailure("PROVIDER_TIMEOUT",false); assertEquals(DeviceState.UNKNOWN,service.status(id).state());
        assertNotNull(service.get(id).stateCheckedAt());
    }
    @Test void cachedStatusQueryNeverPreparesProviderOrChangesDatabase() {
        long id=device("4000000001");
        var checked=java.time.LocalDateTime.of(2026,1,1,0,0);
        jdbc.update("UPDATE yshop_printer SET device_state='PAPER_OUT',state_checked_at=?,last_error='SYNTHETIC_CACHED_STATUS' WHERE id=?",checked,id);
        Map<String,Object> before=new TreeMap<>();
        for(String table:List.of("yshop_printer","yshop_printer_binding","yshop_printer_test_job","yshop_printer_provider_token"))
            before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        fake.preparationFailure=new PrinterFailure("PROVIDER_TIMEOUT",false);
        var controller=new co.yixiang.yshop.module.store.controller.admin.printer.PrinterController(service);
        var cached=controller.status(id).getData();
        assertEquals(DeviceState.PAPER_OUT,cached.state()); assertEquals(checked,cached.checkedAt());
        assertEquals("SYNTHETIC_CACHED_STATUS",cached.errorCode());
        assertEquals(0,fake.sessionCalls.get());
        Map<String,Object> after=new TreeMap<>();
        for(String table:before.keySet()) after.put(table,jdbc.queryForList("SELECT * FROM "+table));
        assertEquals(before,after);
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_provider_token",Long.class));
        login(2,0); assertThrows(PrinterFailure.class,()->controller.status(id));
        headquartersRoles.clear(); login(1,1); assertThrows(PrinterFailure.class,()->controller.status(id));
    }
    @Test void testPrintingIsIdempotentAndTimeoutDoesNotResubmit() {
        long id=device("4000000001"); service.authorize(id,false);
        TestRequest request=new TestRequest(id,"test-request-0000001");
        assertEquals(JobState.ACCEPTED,service.test(request).state()); assertEquals(JobState.ACCEPTED,service.test(request).state()); assertEquals(1,fake.printCalls.get());
        fake.printFailure=new PrinterFailure("PROVIDER_TIMEOUT",true);
        TestRequest unknown=new TestRequest(id,"test-request-0000002");
        assertEquals(JobState.UNKNOWN,service.test(unknown).state()); assertEquals(JobState.UNKNOWN,service.test(unknown).state()); assertEquals(2,fake.printCalls.get());
        assertEquals(2L,service.jobs(id,1,10).getTotal());
    }
    @Test void unknownTestPrintKeepsDeviceBusyUntilAuditedRecovery() {
        long id=device("4000000001"); service.authorize(id,false);
        fake.printFailure=new PrinterFailure("PROVIDER_TIMEOUT",true);
        TestRequest request=new TestRequest(id,"test-unknown-recovery-01");
        assertEquals(JobState.UNKNOWN,service.test(request).state());

        PendingOperation pending=service.pendingOperation(id);
        assertNotNull(pending);
        assertEquals("TEST_PRINT",pending.operationType());
        assertEquals(request.requestId(),pending.requestId());
        assertThrows(PrinterFailure.class,()->service.bind(new BindingRequest(id,1L)));

        var resolved=service.resolveOperation(new ResolveOperationRequest(id,pending.operationToken(),
                "已人工确认测试打印发送停止且供应商工作结束",true,true));
        assertEquals("TEST_PRINT_UNKNOWN",resolved.outcome());
        assertNull(service.pendingOperation(id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_operation_resolution WHERE tenant_id=1 AND printer_id=? AND request_id=?",
                Integer.class,id,request.requestId()));
        service.bind(new BindingRequest(id,1L));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_binding WHERE tenant_id=1 AND printer_id=? AND shop_id=1",
                Integer.class,id));
    }
    @Test void testPrintCrashKeepsIntentAndCannotRepeatExternalCall() {
        long id=device("4000000001"); service.authorize(id,false); fake.crash=true;
        TestRequest request=new TestRequest(id,"test-request-crash01"); assertThrows(IllegalStateException.class,()->service.test(request));
        fake.crash=false; assertEquals(JobState.SUBMITTING,service.test(request).state()); assertEquals(1,fake.printCalls.get());
        PendingOperation pending=service.pendingOperation(id);
        assertEquals("TEST_PRINT",pending.operationType()); assertEquals(request.requestId(),pending.requestId());
        assertThrows(PrinterFailure.class,()->service.resolveOperation(new ResolveOperationRequest(id,pending.operationToken(),"尚未核对设备结果",true,false)));
        var resolved=service.resolveOperation(new ResolveOperationRequest(id,pending.operationToken(),"设备端确认没有生成该测试小票",true,true));
        assertEquals("TEST_PRINT_UNKNOWN",resolved.outcome());
        assertEquals(JobState.UNKNOWN,service.getJob(id,request.requestId()).state());
        assertEquals(1,fake.printCalls.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_operation_resolution WHERE tenant_id=1 AND printer_id=? AND request_id=?",Integer.class,id,request.requestId()));
    }
    @Test void concurrentBindingKeepsOneOwner() throws Exception {
        long id=device("4000000001"); CountDownLatch start=new CountDownLatch(1); var executor=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(long shop:List.of(1L,2L)) results.add(executor.submit(()->{ login(1,0); start.await();
                try { service.bind(new BindingRequest(id,shop)); return true; } catch(PrinterFailure expected) { return false; }
                finally { cleanup(); } }));
            start.countDown(); int winners=0; for(var result:results) if(result.get(15,TimeUnit.SECONDS)) winners++;
            assertEquals(1,winners); assertNotNull(service.get(id).shopId());
        } finally { executor.shutdownNow(); }
    }
    @Test void concurrentTestRequestsSendOnlyOneReceipt() throws Exception {
        long id=device("4000000001"); service.authorize(id,false); var executor=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1); List<Future<JobView>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(executor.submit(()->{ login(1,0); start.await();
                try { return service.test(new TestRequest(id,"test-concurrent-0001")); } finally { cleanup(); } }));
            start.countDown(); for(var result:results) assertNotNull(result.get(15,TimeUnit.SECONDS)); assertEquals(1,fake.printCalls.get());
        } finally { executor.shutdownNow(); }
    }
    @Test void secondVendorUsesSameServiceAndReceiptContract() {
        long id=service.save(new SaveRequest(null,"另一设备","SECOND_VENDOR","device-b","","CLOUD",true,Map.of()),false);
        service.authorize(id,false); service.bind(new BindingRequest(id,1L));
        assertEquals(JobState.ACCEPTED,service.test(new TestRequest(id,"test-second-vendor01")).state());
        assertEquals("SECOND_VENDOR",service.get(id).providerCode());
    }
    @Test void tokenPersistedEncryptedAndConcurrentCallsFetchOnce() throws Exception {
        YilianyunTokenStore tokens=new YilianyunTokenStore(repo,cipher,manager); AtomicInteger calls=new AtomicInteger();
        var executor=Executors.newFixedThreadPool(3);
        try {
            List<Future<String>> results=new ArrayList<>(); for(int i=0;i<3;i++) results.add(executor.submit(()->tokens.get("synthetic-account",()->{calls.incrementAndGet();return "synthetic-token";})));
            for(var result:results) assertEquals("synthetic-token",result.get(15,TimeUnit.SECONDS));
            assertEquals(1,calls.get()); assertFalse(jdbc.queryForObject("SELECT encrypted_token FROM yshop_printer_provider_token",String.class).contains("synthetic-token"));
            assertEquals("synthetic-token",new YilianyunTokenStore(repo,cipher,manager).get("synthetic-account",()->{fail("must reuse persisted token");return "";}));
        } finally { executor.shutdownNow(); }
    }
    @Test void sessionAndIoEntrypointsRejectAnExistingTransactionBeforeExternalWork() {
        long id=device("4000000001");
        var tokens=new YilianyunTokenStore(repo,cipher,manager);
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            assertEquals("SESSION_IN_TRANSACTION",assertThrows(PrinterFailure.class,fake::openSession).getMessage());
            assertThrows(PrinterFailure.class,()->tokens.get("guard-account",()->{fail("no token fetch");return "";}));
            assertThrows(PrinterFailure.class,()->service.authorize(id,false));
            assertThrows(PrinterFailure.class,()->service.reconnect(id));
            assertThrows(PrinterFailure.class,()->service.status(id));
            assertThrows(PrinterFailure.class,()->service.test(new TestRequest(id,"guard-request-00001")));
        });
        assertEquals(0,fake.sessionCalls.get());
        assertEquals(Authorization.UNAUTHORIZED,service.get(id).authorization());
    }
    @Test void reconnectVerifiesAndRepairsCloudStateWithoutDroppingShopBinding() {
        long id=device("4000000001"); service.authorize(id,false); service.bind(new BindingRequest(id,1L));
        assertEquals(Authorization.AUTHORIZED,service.reconnect(id).authorization());
        assertEquals(1,fake.authorizeCalls.get()); assertEquals(DeviceState.ONLINE,service.get(id).deviceState());
        fake.statusFailure=new PrinterFailure("PROVIDER_REJECTED_42",false);
        assertEquals(Authorization.AUTHORIZED,service.reconnect(id).authorization());
        assertEquals(2,fake.authorizeCalls.get()); assertEquals(1L,service.get(id).shopId());
        fake.authorizeFailure=new PrinterFailure("PROVIDER_REJECTED_43",false);
        var failed=service.reconnect(id);
        assertEquals(Authorization.UNKNOWN,failed.authorization()); assertEquals("PROVIDER_REJECTED_43",failed.errorCode());
        assertEquals(3,fake.authorizeCalls.get()); assertEquals(1L,service.get(id).shopId());
        assertThrows(PrinterFailure.class,()->service.authorize(id,true));
    }
    @Test void reconnectPreparationFailureDoesNotReportCachedAuthorizationAsSuccess() {
        long id=device("4000000001"); service.authorize(id,false);
        fake.preparationFailure=new PrinterFailure("PROVIDER_TIMEOUT",false);
        var result=service.reconnect(id);
        assertEquals(Authorization.UNKNOWN,result.authorization()); assertEquals("PROVIDER_TIMEOUT",result.errorCode());
        assertEquals(1,fake.authorizeCalls.get());
    }
    @Test void expiredAuthorizationIntentNeverDispatchesAfterSessionPreparation() {
        long id=device("4000000001");
        fake.preparation=()->jdbc.update("UPDATE yshop_printer SET authorization_operation_at=? WHERE id=?",java.time.LocalDateTime.now().minusMinutes(2),id);
        var result=service.authorize(id,false);
        assertEquals(Authorization.UNKNOWN,result.authorization()); assertEquals("RESULT_UNKNOWN",result.errorCode());
        assertEquals(0,fake.authorizeCalls.get());
    }
    @Test void missingRequestLookupIsReadOnlyAndExplicitSubmissionReusesItsId() {
        long id=device("4000000001"); var request=new TestRequest(id,"lookup-request-0001");
        assertThrows(PrinterFailure.class,()->service.test(request));
        assertNull(service.getJob(id,request.requestId()));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_printer_test_job",Long.class));
        assertEquals(0,fake.sessionCalls.get());
        service.authorize(id,false); service.test(request);
        assertEquals(JobState.ACCEPTED,service.getJob(id,request.requestId()).state());
        service.test(request); assertEquals(1,fake.printCalls.get());
    }
    @Test void staleLookupDoesNotChangeTheDatabaseOrDispatchAnyPrint() {
        long id=device("4000000001"); service.authorize(id,false); fake.crash=true;
        var request=new TestRequest(id,"lookup-crash-00001"); assertThrows(IllegalStateException.class,()->service.test(request));
        jdbc.update("UPDATE yshop_printer_test_job SET create_time=? WHERE request_id=?",java.time.LocalDateTime.now().minusMinutes(2),request.requestId());
        int sessions=fake.sessionCalls.get();
        assertEquals(JobState.UNKNOWN,service.getJob(id,request.requestId()).state());
        assertEquals("SUBMITTING",jdbc.queryForObject("SELECT state FROM yshop_printer_test_job WHERE request_id=?",String.class,request.requestId()));
        assertEquals(sessions,fake.sessionCalls.get()); assertEquals(1,fake.printCalls.get());
    }
    @Test void expiredTestIntentNeverDispatchesAfterSessionPreparation() {
        long id=device("4000000001"); service.authorize(id,false);
        fake.preparation=()->jdbc.update("UPDATE yshop_printer_test_job SET create_time=?",java.time.LocalDateTime.now().minusMinutes(2));
        var request=new TestRequest(id,"expired-print-00001");
        assertEquals(JobState.UNKNOWN,service.test(request).state());
        assertEquals(JobState.UNKNOWN,service.test(request).state()); assertEquals(0,fake.printCalls.get());
    }
    @Test void preparationFailureEndsThePrintWithoutCallingTheDevice() {
        long id=device("4000000001"); service.authorize(id,false);
        fake.preparationFailure=new PrinterFailure("PROVIDER_TIMEOUT",false);
        var request=new TestRequest(id,"prepare-print-00001");
        assertEquals(JobState.FAILED,service.test(request).state()); assertEquals(0,fake.printCalls.get());
        assertEquals(JobState.FAILED,service.getJob(id,request.requestId()).state());
    }
    @Test void deletedDevicesKeepReadableHistoryButAllDeviceOperationsRemainBlocked() {
        long id=device("4000000001"); service.authorize(id,false);
        var request=new TestRequest(id,"deleted-record-0001"); service.test(request);
        service.authorize(id,true); service.delete(id);
        assertEquals(0L,service.page(1,10,null,null).getTotal());
        assertTrue(service.page(1,10,null,null,true).getList().get(0).deleted());
        assertEquals(1L,service.jobs(id,1,10).getTotal());
        assertEquals(JobState.ACCEPTED,service.getJob(id,request.requestId()).state());
        assertThrows(PrinterFailure.class,()->service.get(id));
        assertThrows(PrinterFailure.class,()->service.save(request(id,"4000000001",Map.of()),true));
        assertThrows(PrinterFailure.class,()->service.bind(new BindingRequest(id,1L)));
        assertThrows(PrinterFailure.class,()->service.unbind(id));
        assertThrows(PrinterFailure.class,()->service.authorize(id,false));
        assertThrows(PrinterFailure.class,()->service.reconnect(id));
        assertThrows(PrinterFailure.class,()->service.status(id));
        assertThrows(PrinterFailure.class,()->service.test(request));
        login(2,0); assertEquals(0L,service.page(1,10,null,null,true).getTotal());
        assertThrows(PrinterFailure.class,()->service.jobs(id,1,10));
        assertThrows(PrinterFailure.class,()->service.getJob(id,request.requestId()));
        headquartersRoles.clear(); login(1,1); assertThrows(PrinterFailure.class,()->service.jobs(id,1,10));
        assertThrows(PrinterFailure.class,()->service.getJob(id,request.requestId()));
    }
    @Test void requestLookupCannotMixDevicesEvenInsideTheSameTenant() {
        long one=device("4000000001"),two=device("4000000002"); service.authorize(one,false);
        var request=new TestRequest(one,"device-lookup-0001"); service.test(request);
        assertEquals("REQUEST_CONFLICT",assertThrows(PrinterFailure.class,()->service.getJob(two,request.requestId())).getMessage());
    }
    @Test void devicesWithoutAuthorizationUseUsbAndLanThroughTheSameBusinessService() {
        FakeProvider local=new FakeProvider("LOCAL_TEST",false);
        var localService=new PrinterService(repo,new PrinterProviderRegistry(List.of(local)),cipher,new TransactionTemplate(manager),printerAccess,false);
        for(String connection:List.of("USB","LAN")) {
            long id=localService.save(new SaveRequest(null,"本地设备","LOCAL_TEST","local-"+connection,"",connection,true,Map.of()),false);
            assertEquals(Authorization.UNAUTHORIZED,localService.get(id).authorization());
            assertThrows(PrinterFailure.class,()->localService.authorize(id,false));
            localService.bind(new BindingRequest(id,1L));
            assertEquals(DeviceState.ONLINE,localService.status(id).state());
            assertEquals(JobState.ACCEPTED,localService.test(new TestRequest(id,"local-print-0001-"+connection)).state());
            localService.unbind(id); localService.delete(id);
        }
        assertEquals(2,local.printCalls.get());
    }
    @Test void orderPrintSupportsLocalDeviceThatDoesNotRequireAuthorization() {
        FakeProvider local=new FakeProvider("LOCAL_ORDER_TEST",false);
        var localProviders=new PrinterProviderRegistry(List.of(local));
        var localService=new PrinterService(repo,localProviders,cipher,new TransactionTemplate(manager),printerAccess,false);
        long id=localService.save(new SaveRequest(null,"本地订单设备","LOCAL_ORDER_TEST","local-order-1","","USB",true,Map.of()),false);
        localService.bind(new BindingRequest(id,1L));
        localService.status(id);
        long event=insertPrintEvent("local-order-print-event-0001",1L);
        var worker=new OrderPrintJobWorker(jdbc,manager,localService,localProviders);

        worker.processPendingEvents();
        assertEquals("PROCESSED",jdbc.queryForObject("SELECT state FROM yshop_order_fulfillment_print_event WHERE id=?",String.class,event));
        worker.dispatchPendingJobs();

        assertEquals(1,local.printCalls.get());
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT state FROM yshop_order_print_job WHERE event_id=?",String.class,event));
        assertEquals(Authorization.UNAUTHORIZED,localService.get(id).authorization());
    }
    @Test void twentyRequestsShareTwoConnectionsWithoutNestedTokenTransactions() throws Exception {
        Properties properties=new Properties(); String url,user,password;
        if(mysql()) {
            try(var input=Files.newInputStream(Path.of(System.getenv("PRINTER_ACCEPTANCE_CONFIG")))) { properties.load(input); }
            url=properties.getProperty("url"); user=properties.getProperty("username"); password=properties.getProperty("password");
        } else {
            try(var connection=jdbc.getDataSource().getConnection()) { url=connection.getMetaData().getURL(); }
            user=""; password="";
        }
        try(var pool=new DruidDataSource()) {
            pool.setUrl(url); pool.setUsername(user); pool.setPassword(password); pool.setMaxActive(2);
            pool.setMaxWait(3000); pool.setValidationQuery("SELECT 1");
            var poolManager=new DataSourceTransactionManager(pool);
            var poolRepo=new PrinterRepository(new JdbcTemplate(pool));
            var tokens=new YilianyunTokenStore(poolRepo,cipher,poolManager);
            AtomicInteger fetches=new AtomicInteger();
            FakeProvider adapter=new FakeProvider("POOL_TEST",false) {
                @Override public PrinterSession openSession() {
                    PrinterProvider.assertOutsideTransaction();
                    assertEquals("pool-token",tokens.get("pool-account",()->{fetches.incrementAndGet();return "pool-token";}));
                    return this;
                }
            };
            var pooled=new PrinterService(poolRepo,new PrinterProviderRegistry(List.of(adapter)),cipher,new TransactionTemplate(poolManager),printerAccess,false);
            List<Long> ids=new ArrayList<>();
            for(int i=0;i<20;i++) ids.add(pooled.save(new SaveRequest(null,"并发设备","POOL_TEST","pool-"+i,"","LAN",true,Map.of()),false));
            var workers=Executors.newFixedThreadPool(21); CountDownLatch start=new CountDownLatch(1);
            try {
                List<Future<DeviceState>> results=new ArrayList<>();
                for(long id:ids) results.add(workers.submit(()->{login(1,0); try {start.await();return pooled.status(id).state();} finally {cleanup();}}));
                Future<Integer> ordinary=workers.submit(()->{start.await();return new JdbcTemplate(pool).queryForObject("SELECT 1",Integer.class);});
                start.countDown(); assertEquals(1,ordinary.get(5,TimeUnit.SECONDS));
                for(var result:results) assertEquals(DeviceState.ONLINE,result.get(10,TimeUnit.SECONDS));
                assertEquals(1,fetches.get());
                assertEquals("pool-token",tokens.get("pool-account",()->{fail("persisted token must be reused");return "";}));
            } finally { workers.shutdownNow(); }
        }
    }
    static class FakeProvider implements PrinterProvider, PrinterSession {
        final String code; final AtomicInteger printCalls=new AtomicInteger(); final List<Receipt> receipts=new CopyOnWriteArrayList<>();
        volatile PrinterFailure authorizeFailure,printFailure,statusFailure; volatile boolean crash;
        DeviceState deviceState=DeviceState.ONLINE;
        final boolean requiresAuthorization;
        Runnable preparation=()->{};
        volatile Runnable beforePrint=()->{};
        volatile Runnable beforeStatus=()->{};
        final AtomicInteger authorizeCalls=new AtomicInteger(),sessionCalls=new AtomicInteger();
        PrinterFailure preparationFailure;
        FakeProvider(String code) { this(code,true); }
        FakeProvider(String code,boolean requiresAuthorization) { this.code=code; this.requiresAuthorization=requiresAuthorization; }
        public PrinterSession openSession() { PrinterProvider.assertOutsideTransaction(); sessionCalls.incrementAndGet(); preparation.run(); if(preparationFailure!=null) throw preparationFailure; return this; }
        public ProviderInfo info() { return new ProviderInfo(code,code,requiresAuthorization?List.of("CLOUD"):List.of("USB","LAN"),List.of(new Field("terminalKey","密钥",true,false)),new Capabilities(requiresAuthorization,requiresAuthorization,true,true),true,requiresAuthorization); }
        public void validate(String device,String connection,Map<String,String> config) {}
        public void authorize(String device,String name,Map<String,String> config) { authorizeCalls.incrementAndGet(); if(crash) throw new IllegalStateException("SIMULATED_CRASH"); if(authorizeFailure!=null) throw authorizeFailure; }
        public void deauthorize(String device) { if(crash) throw new IllegalStateException("SIMULATED_CRASH"); }
        public DeviceState status(String device) { beforeStatus.run();if(statusFailure!=null) throw statusFailure;return deviceState; }
        public PrintResult print(String device,String request,Receipt receipt) { printCalls.incrementAndGet(); receipts.add(receipt); beforePrint.run(); if(crash) throw new IllegalStateException("SIMULATED_CRASH"); if(printFailure!=null) throw printFailure; assertEquals(1,receipt.copies()); return new PrintResult("123456789"); }
    }
}
