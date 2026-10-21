package co.yixiang.yshop.module.order.fulfillment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.framework.common.exception.ServiceException;
import co.yixiang.yshop.framework.web.core.handler.GlobalExceptionHandler;
import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.framework.mybatis.core.handler.DefaultDBFieldHandler;
import co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.dataobject.userbill.UserBillDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper;
import co.yixiang.yshop.module.member.service.userbill.UserBillServiceImpl;
import co.yixiang.yshop.module.order.service.fulfillment.OrderFulfillmentRewardService;
import co.yixiang.yshop.module.order.service.fulfillment.OrderFulfillmentService;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import co.yixiang.yshop.module.system.api.permission.PermissionApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.ibatis.session.SqlSessionFactory;

import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_IDEMPOTENCY_CONFLICT;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_STATE_CONFLICT;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_VERSION_CONFLICT;

class OrderFulfillmentDatabaseTest {
    JdbcTemplate jdbc;
    PlatformTransactionManager transactions;
    OrderFulfillmentService service;
    OrderFulfillmentRewardService rewards;
    OrderFulfillmentRewardService realRewards;
    MemberUserMapper memberUserMapper;
    UserBillMapper userBillMapper;
    DriverManagerDataSource dataSource;
    PermissionApi permissions;
    boolean headquarters = true;
    boolean mysql;
    long orderId;
    long shopOneId;
    long shopTwoId;
    long syntheticMemberId;
    Path boot;

    @BeforeEach void setup() throws Exception {
        dataSource = new DriverManagerDataSource();
        String config = System.getenv("YSHOP_ORDERING_ACCEPTANCE_CONFIG");
        mysql = config != null;
        if (mysql) {
            var properties = new Properties();
            try (var input = Files.newInputStream(Path.of(config))) { properties.load(input); }
            assertTrue(properties.getProperty("url").matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]{2,5}/yshop_acceptance_phase6a_[a-f0-9]{8}[?].*"));
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(properties.getProperty("url"));
            dataSource.setUsername(properties.getProperty("username"));
            dataSource.setPassword(properties.getProperty("password"));
        } else {
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:fulfillment_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000");
            dataSource.setUsername("sa");
        }
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        boot = Path.of("").toAbsolutePath();
        while (!Files.exists(boot.resolve("sql/yixiang-drink-open.sql"))) boot = boot.getParent();
        orderId = 900_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000);
        shopOneId = 910_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000);
        shopTwoId = shopOneId + 1;
        syntheticMemberId = 920_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000);
        if (mysql) ensureMysqlFixtures();
        else {
            jdbc.execute("CREATE TABLE yshop_store_shop(id BIGINT PRIMARY KEY,name VARCHAR(100),mobile VARCHAR(20),images VARCHAR(100),address VARCHAR(100),address_map VARCHAR(100),start_time TIMESTAMP,end_time TIMESTAMP,lng VARCHAR(30),lat VARCHAR(30),status INT,admin_id VARCHAR(100),deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE yshop_store_order(id BIGINT PRIMARY KEY,order_id VARCHAR(32) UNIQUE,uid BIGINT,shop_id BIGINT,tenant_id BIGINT DEFAULT 0,order_type VARCHAR(32),paid INT,status INT,refund_status INT,is_system_del INT DEFAULT 0,deleted BIT DEFAULT 0,gain_integral DECIMAL(10,2),pay_type VARCHAR(32) NOT NULL,mark VARCHAR(512) NOT NULL,cost DECIMAL(10,2) NOT NULL,create_time TIMESTAMP NOT NULL,update_time TIMESTAMP,fulfillment_status VARCHAR(24) DEFAULT 'LEGACY' NOT NULL,fulfillment_version INT DEFAULT 0 NOT NULL)");
            jdbc.execute("CREATE TABLE yshop_order_fulfillment_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT NOT NULL,request_key VARCHAR(64) NOT NULL UNIQUE,request_hash CHAR(64) NOT NULL,action VARCHAR(24) NOT NULL,from_status VARCHAR(24) NOT NULL,to_status VARCHAR(24) NOT NULL,expected_version INT,result_version INT NOT NULL,actor_id BIGINT NOT NULL,actor_type VARCHAR(16) NOT NULL,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            setupMemberTables(false);
        }
        if (mysql) setupMemberTables(true);
        if (mysql) {
            String migration = Files.readString(boot.resolve("sql/migrations/2026-10-22-order-fulfillment.sql"));
            transaction(() -> {
                for (String sql : migration.split(";"))
                    if (!sql.isBlank()) jdbc.execute(sql);
                return null;
            });
        }
        jdbc.update("DELETE FROM yshop_order_fulfillment_log");
        insertShops();
        rewards = mock(OrderFulfillmentRewardService.class);
        permissions = mock(PermissionApi.class);
        when(permissions.hasAnyRoles(anyLong(), any(String[].class))).thenAnswer(call -> headquarters);
        realRewards = createRealRewards();
        service = createService(rewards);
    }

    private void ensureMysqlFixtures() throws Exception {
        String ddl = Files.readString(boot.resolve("sql/yixiang-drink-open.sql"));
        for (String table : new String[] {"yshop_store_shop", "yshop_store_order", "yshop_user", "yshop_user_bill"}) {
            if (jdbcTableMissing(table)) {
                var matcher = java.util.regex.Pattern.compile("CREATE TABLE `" + table + "`.*?;", java.util.regex.Pattern.DOTALL).matcher(ddl);
                assertTrue(matcher.find(), "Missing canonical DDL for " + table);
                jdbc.execute(matcher.group());
            }
        }
    }

    private void setupMemberTables(boolean mysqlSchema) throws Exception {
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        var global = GlobalConfigUtils.defaults();
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        bean.setGlobalConfig(global);
        var config = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MemberUserMapper.class);
        config.addMapper(UserBillMapper.class);
        bean.setConfiguration(config);
        SqlSessionFactory factory = bean.getObject();
        var sql = new SqlSessionTemplate(factory);
        memberUserMapper = sql.getMapper(MemberUserMapper.class);
        userBillMapper = sql.getMapper(UserBillMapper.class);
        if (!mysqlSchema) {
            createEntityTable(MemberUserDO.class);
            createEntityTable(UserBillDO.class);
        }
    }

    private void createEntityTable(Class<?> type) {
        var table = TableInfoHelper.getTableInfo(type);
        var columns = new java.util.ArrayList<String>();
        columns.add(table.getKeyColumn() + " BIGINT PRIMARY KEY");
        for (var field : table.getFieldList()) {
            Class<?> propertyType = field.getPropertyType();
            String sqlType = propertyType == BigDecimal.class ? "DECIMAL(20,4)"
                    : propertyType == LocalDateTime.class ? "TIMESTAMP"
                    : Number.class.isAssignableFrom(propertyType) ? "BIGINT"
                    : propertyType == Boolean.class ? "BOOLEAN" : "VARCHAR(2048)";
            columns.add(field.getColumn() + " " + sqlType
                    + (field.getColumn().equals("deleted") ? " DEFAULT 0" : ""));
        }
        jdbc.execute("CREATE TABLE " + table.getTableName() + " (" + String.join(",", columns) + ")");
    }

    private OrderFulfillmentRewardService createRealRewards() {
        var billService = new UserBillServiceImpl();
        ReflectionTestUtils.setField(billService, "userBillMapper", userBillMapper);
        return new OrderFulfillmentRewardService(memberUserMapper, billService);
    }

    private OrderFulfillmentService createService(OrderFulfillmentRewardService rewardService) {
        return new OrderFulfillmentService(jdbc, new StoreAccessService(jdbc, permissions), rewardService);
    }

    private boolean jdbcTableMissing(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", Integer.class, table) == 0;
    }

    private void insertShops() {
        for (int id = 1; id <= 2; id++) {
            long shopId = id == 1 ? shopOneId : shopTwoId;
            jdbc.update("INSERT INTO yshop_store_shop(id,name,mobile,images,address,address_map,start_time,end_time,lng,lat,status,admin_id) VALUES(?,?,'','[]','','','2026-10-08 00:00:00','2026-10-08 00:00:00','0','0',1,?)",
                    shopId, "Synthetic fulfillment shop " + id, id == 1 ? "101,103" : "102,103");
        }
    }

    private void insertOrder(String status, int version, int paid, int shop) {
        insertOrder(status, version, paid, shop, 1L);
    }

    private void insertOrder(String status, int version, int paid, int shop, long uid) {
        String orderCode = "fulfillment-" + orderId;
        jdbc.update("INSERT INTO yshop_store_order(id,order_id,uid,shop_id,order_type,paid,status,refund_status,is_system_del,deleted,gain_integral,pay_type,mark,cost,create_time,fulfillment_status,fulfillment_version) VALUES(?,?,?,?, 'takein',?,0,0,0,0,2.50,'synthetic','Synthetic pickup',1,CURRENT_TIMESTAMP,?,?)",
                orderId, orderCode, uid, shop == 1 ? shopOneId : shopTwoId, paid, status, version);
    }

    private void login(long userId, int userType) {
        var user = new LoginUser(); user.setId(userId); user.setUserType(userType); user.setTenantId(0L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    }

    private <T> T transaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private String state() {
        return jdbc.queryForObject("SELECT fulfillment_status FROM yshop_store_order WHERE id=?", String.class, orderId);
    }

    @Test void transitionRejectsStaleVersion() {
        insertOrder("WAIT_ACCEPT", 2, 1, 1); login(101, 2);
        var failure = assertThrows(ServiceException.class, () -> transaction(() -> service.transitionStaff(orderId, "ACCEPT", 1, "accept-stale-0001")));
        assertEquals(FULFILLMENT_VERSION_CONFLICT.getCode(), failure.getCode());
        assertEquals(1008007034, new GlobalExceptionHandler("test", null).serviceExceptionHandler(failure).getCode());
        assertEquals("WAIT_ACCEPT", state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log", Integer.class));
        verifyNoInteractions(rewards);
    }

    @Test void unpaidOrderCannotEnterFulfillment() {
        insertOrder("WAIT_ACCEPT", 1, 0, 1); login(101, 2);
        var failure = assertThrows(ServiceException.class, () -> transaction(() -> service.transitionStaff(orderId, "ACCEPT", 1, "unpaid-accept-0001")));
        assertEquals(FULFILLMENT_STATE_CONFLICT.getCode(), failure.getCode());
        assertEquals("WAIT_ACCEPT", state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log WHERE order_id=?", Integer.class, orderId));
    }

    @Test void concurrentAcceptUsesOneVersionWinner() throws Exception {
        insertOrder("WAIT_ACCEPT", 1, 1, 1);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> concurrentAccept("accept-race-0001", ready, start));
            var second = pool.submit(() -> concurrentAccept("accept-race-0002", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var outcomes = java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("APPLIED"::equals).count());
            assertEquals(1, outcomes.stream().filter(FULFILLMENT_VERSION_CONFLICT.getCode().toString()::equals).count());
        } finally {
            pool.shutdownNow();
        }
        assertEquals("ACCEPTED", state());
        assertEquals(2, jdbc.queryForObject("SELECT fulfillment_version FROM yshop_store_order WHERE id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log WHERE order_id=?", Integer.class, orderId));
        verifyNoInteractions(rewards);
    }

    private String concurrentAccept(String requestKey, CountDownLatch ready, CountDownLatch start) throws Exception {
        login(101, 2);
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            transaction(() -> service.transitionStaff(orderId, "ACCEPT", 1, requestKey));
            return "APPLIED";
        } catch (RuntimeException failure) {
            return failure instanceof ServiceException serviceException
                    ? serviceException.getCode().toString() : failure.getMessage();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }
    }

    @Test void legacyTakeCannotSkipReady() {
        insertOrder("WAIT_ACCEPT", 1, 1, 1); login(1, 1);
        var failure = assertThrows(ServiceException.class, () -> transaction(() -> service.legacyMemberTake(orderId, 1L)));
        assertEquals(FULFILLMENT_STATE_CONFLICT.getCode(), failure.getCode());
        assertEquals("WAIT_ACCEPT", state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log", Integer.class));
        verifyNoInteractions(rewards);
    }

    @Test void collectAwardsOnce() {
        insertOrder("READY", 4, 1, 1); login(1, 1);
        var first = transaction(() -> service.transitionMember(orderId, 1L, "COLLECT", 4, "collect-request-0001"));
        var replay = transaction(() -> service.transitionMember(orderId, 1L, "COLLECT", 4, "collect-request-0001"));
        assertEquals("COLLECTED", first.status()); assertEquals(5, first.version()); assertTrue(first.applied());
        assertEquals(first.status(), replay.status()); assertEquals(first.version(), replay.version()); assertFalse(replay.applied());
        assertEquals("COLLECTED", state());
        assertEquals(3, jdbc.queryForObject("SELECT status FROM yshop_store_order WHERE id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log WHERE order_id=?", Integer.class, orderId));
        verify(rewards, times(1)).award(any(StoreOrderDO.class));
    }

    @Test void collectionPersistsMemberPointsAndBillOnce() {
        jdbc.update("INSERT INTO yshop_user(id,integral,deleted) VALUES(?,?,0)", syntheticMemberId, new BigDecimal("10.00"));
        insertOrder("READY", 4, 1, 1, syntheticMemberId); login(syntheticMemberId, 1);
        service = createService(realRewards);
        var first = transaction(() -> service.transitionMember(orderId, syntheticMemberId, "COLLECT", 4, "real-reward-collect-0001"));
        var replay = transaction(() -> service.transitionMember(orderId, syntheticMemberId, "COLLECT", 4, "real-reward-collect-0001"));
        assertTrue(first.applied()); assertFalse(replay.applied());
        assertEquals(new BigDecimal("12.5000"), memberUserMapper.selectById(syntheticMemberId).getIntegral());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_user_bill WHERE uid=? AND link_id=? AND title='购买商品赠送积分'", Integer.class, syntheticMemberId, Long.toString(orderId)));
        assertEquals(new BigDecimal("2.5000"), jdbc.queryForObject("SELECT number FROM yshop_user_bill WHERE uid=? AND link_id=?", BigDecimal.class, syntheticMemberId, Long.toString(orderId)));
        assertEquals(new BigDecimal("12.5000"), jdbc.queryForObject("SELECT balance FROM yshop_user_bill WHERE uid=? AND link_id=?", BigDecimal.class, syntheticMemberId, Long.toString(orderId)));
    }

    @Test void realRewardWriteFailureRollsBackMemberOrderAndAudit() {
        jdbc.update("INSERT INTO yshop_user(id,integral,deleted) VALUES(?,?,0)", syntheticMemberId, new BigDecimal("7.00"));
        insertOrder("READY", 4, 1, 1, syntheticMemberId); login(syntheticMemberId, 1);
        service = createService(realRewards);
        jdbc.execute("ALTER TABLE yshop_user_bill ADD CONSTRAINT fulfillment_reward_test_reject CHECK (number < 0)");
        try {
            assertThrows(RuntimeException.class, () -> transaction(() -> service.transitionMember(
                    orderId, syntheticMemberId, "COLLECT", 4, "real-reward-rollback-0001")));
            assertEquals(new BigDecimal("7.0000"), memberUserMapper.selectById(syntheticMemberId).getIntegral());
            assertEquals("READY", state());
            assertEquals(4, jdbc.queryForObject("SELECT fulfillment_version FROM yshop_store_order WHERE id=?", Integer.class, orderId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log WHERE order_id=?", Integer.class, orderId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_user_bill WHERE uid=? AND link_id=?", Integer.class, syntheticMemberId, Long.toString(orderId)));
        } finally {
            jdbc.execute("ALTER TABLE yshop_user_bill DROP " + (mysql ? "CHECK " : "CONSTRAINT ") + "fulfillment_reward_test_reject");
        }
    }

    @Test void paymentCompletesIntoWaitAccept() {
        insertOrder("LEGACY", 0, 1, 1);
        transaction(() -> { service.initializePaidPickup(prePaymentOrder()); return null; });
        assertEquals("WAIT_ACCEPT", state());
        assertEquals(1, jdbc.queryForObject("SELECT fulfillment_version FROM yshop_store_order WHERE id=?", Integer.class, orderId));
        assertEquals("PAYMENT_CONFIRMED", jdbc.queryForObject("SELECT action FROM yshop_order_fulfillment_log WHERE order_id=?", String.class, orderId));
    }

    @Test void historicalPaidPickupRemainsLegacy() {
        insertOrder("LEGACY", 0, 1, 1);
        var historical = prePaymentOrder();
        historical.setPaid(1);
        transaction(() -> { service.initializePaidPickup(historical); return null; });
        assertEquals("LEGACY", state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log WHERE order_id=?", Integer.class, orderId));
    }

    private co.yixiang.yshop.module.order.service.payment.PaymentOrder prePaymentOrder() {
        var order = new co.yixiang.yshop.module.order.service.payment.PaymentOrder();
        order.setId(orderId);
        order.setOrderId("fulfillment-" + orderId);
        order.setUid(1L);
        order.setPaid(0);
        return order;
    }

    @Test void sameKeyDifferentContentIsRejected() {
        insertOrder("WAIT_ACCEPT", 1, 1, 1); login(101, 2);
        transaction(() -> service.transitionStaff(orderId, "ACCEPT", 1, "shared-request-0001"));
        var failure = assertThrows(ServiceException.class, () -> transaction(() -> service.transitionStaff(orderId, "START_PREPARING", 2, "shared-request-0001")));
        assertEquals(FULFILLMENT_IDEMPOTENCY_CONFLICT.getCode(), failure.getCode());
        assertEquals("ACCEPTED", state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log WHERE order_id=?", Integer.class, orderId));
    }

    @Test void rewardFailureRollsBackCollectedStateAndAudit() {
        insertOrder("READY", 4, 1, 1); login(1, 1);
        doThrow(new IllegalStateException("SYNTHETIC_REWARD_FAILURE")).when(rewards).award(any(StoreOrderDO.class));
        var failure = assertThrows(IllegalStateException.class, () -> transaction(() -> service.transitionMember(orderId, 1L, "COLLECT", 4, "collect-rollback-0001")));
        assertEquals("SYNTHETIC_REWARD_FAILURE", failure.getMessage());
        assertEquals("READY", state());
        assertEquals(4, jdbc.queryForObject("SELECT fulfillment_version FROM yshop_store_order WHERE id=?", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log WHERE order_id=?", Integer.class, orderId));
    }

    @Test void staffCannotTransitionAnotherShopOrder() {
        insertOrder("WAIT_ACCEPT", 1, 1, 2); headquarters = false; login(101, 2);
        var failure = assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> transaction(() -> service.transitionStaff(orderId, "ACCEPT", 1, "other-shop-request-01")));
        assertEquals("STORE_ACCESS_DENIED", failure.getMessage());
        assertEquals("WAIT_ACCEPT", state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_fulfillment_log", Integer.class));
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        if (jdbc != null && orderId != 0) {
            jdbc.update("DELETE FROM yshop_order_fulfillment_log WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM yshop_user_bill WHERE uid=? AND link_id=?", syntheticMemberId, Long.toString(orderId));
            jdbc.update("DELETE FROM yshop_store_order WHERE id=?", orderId);
            jdbc.update("DELETE FROM yshop_store_shop WHERE id IN (?,?)", shopOneId, shopTwoId);
            jdbc.update("DELETE FROM yshop_user WHERE id=?", syntheticMemberId);
        }
    }
}
