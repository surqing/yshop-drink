package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.framework.mq.redis.core.RedisMQTemplate;
import co.yixiang.yshop.module.coupon.dal.dataobject.couponuser.CouponUserDO;
import co.yixiang.yshop.module.coupon.service.couponuser.AppCouponUserService;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.dataobject.userbill.UserBillDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper;
import co.yixiang.yshop.module.member.service.user.MemberUserService;
import co.yixiang.yshop.module.member.service.userbill.*;
import co.yixiang.yshop.module.message.mq.producer.WeixinNoticeProducer;
import co.yixiang.yshop.module.order.controller.app.order.vo.AppStoreOrderQueryVo;
import co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO;
import co.yixiang.yshop.module.order.dal.dataobject.storeordercartinfo.StoreOrderCartInfoDO;
import co.yixiang.yshop.module.order.dal.mysql.payment.PaymentMapper;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.dal.mysql.storeorderstatus.StoreOrderStatusMapper;
import co.yixiang.yshop.module.order.mq.consumer.PayNoticeConsumer;
import co.yixiang.yshop.module.order.service.payment.*;
import co.yixiang.yshop.module.order.service.storeorder.*;
import co.yixiang.yshop.module.order.service.storeordercartinfo.StoreOrderCartInfoService;
import co.yixiang.yshop.module.order.service.storeorderstatus.*;
import co.yixiang.yshop.module.pay.callback.*;
import co.yixiang.yshop.module.pay.mq.message.PayNoticeMessage;
import co.yixiang.yshop.module.pay.mq.producer.PayNoticeProducer;
import co.yixiang.yshop.module.product.service.storeproduct.AppStoreProductService;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

/**
 * Production MyBatis SQL + real transactions/effects. Only external transport and unrelated APIs
 * are mocked.
 */
class PaymentDatabaseTest {
    AnnotationConfigApplicationContext ctx;
    JdbcTemplate jdbc;
    PaymentFinalizationService service;
    PaymentMapper payments;
    StoreOrderMapper orders;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean
        DataSource dataSource() {
            var ds = new JdbcDataSource();
            ds.setURL(
                    "jdbc:h2:mem:"
                            + UUID.randomUUID()
                            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");
            return ds;
        }

        @Bean
        JdbcTemplate jdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        PlatformTransactionManager tx(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        SqlSessionFactory factory(DataSource ds) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(ds);
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            for (Class<?> type :
                    List.of(
                            PaymentMapper.class,
                            StoreOrderMapper.class,
                            MemberUserMapper.class,
                            UserBillMapper.class,
                            StoreOrderStatusMapper.class)) config.addMapper(type);
            bean.setConfiguration(config);
            return bean.getObject();
        }

        @Bean
        PaymentMapper payments(SqlSessionFactory f) {
            return new SqlSessionTemplate(f).getMapper(PaymentMapper.class);
        }

        @Bean
        StoreOrderMapper orders(SqlSessionFactory f) {
            return new SqlSessionTemplate(f).getMapper(StoreOrderMapper.class);
        }

        @Bean
        MemberUserMapper memberMapper(SqlSessionFactory f) {
            return new SqlSessionTemplate(f).getMapper(MemberUserMapper.class);
        }

        @Bean
        UserBillMapper billMapper(SqlSessionFactory f) {
            return new SqlSessionTemplate(f).getMapper(UserBillMapper.class);
        }

        @Bean
        StoreOrderStatusMapper statusMapper(SqlSessionFactory f) {
            return new SqlSessionTemplate(f).getMapper(StoreOrderStatusMapper.class);
        }

        @Bean
        AtomicReference<String> fault() {
            return new AtomicReference<>("");
        }

        @Bean
        MemberUserService users(MemberUserMapper mapper, AtomicReference<String> fault) {
            // Actual production incPayCount/selectById SQL on the transaction-bound connection.
            var users = mock(MemberUserService.class);
            doAnswer(
                            call -> {
                                mapper.incPayCount(call.getArgument(0));
                                if ("PAYCOUNT".equals(fault.get()))
                                    throw new IllegalStateException("SYNTHETIC_FAILURE");
                                return null;
                            })
                    .when(users)
                    .incPayCount(anyLong());
            when(users.getUser(anyLong()))
                    .thenAnswer(call -> mapper.selectById((Long) call.getArgument(0)));
            return users;
        }

        @Bean
        UserBillService bills() {
            return new UserBillServiceImpl();
        }

        @Bean
        StoreOrderStatusService statuses() {
            return new StoreOrderStatusServiceImpl();
        }

        @Bean
        AsyncStoreOrderService statistics() {
            return mock(AsyncStoreOrderService.class);
        }

        @Bean
        StoreOrderCartInfoService carts() {
            var carts = mock(StoreOrderCartInfoService.class);
            var item = new StoreOrderCartInfoDO();
            item.setTitle("Synthetic item");
            item.setSpec("synthetic");
            item.setNumber(1);
            item.setProductId(1L);
            when(carts.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                    .thenReturn(List.of(item));
            return carts;
        }

        @Bean
        RedisMQTemplate redisMQTemplate() {
            return mock(RedisMQTemplate.class);
        }

        @Bean
        WeixinNoticeProducer notifications() {
            return mock(WeixinNoticeProducer.class);
        }

        @Bean
        PayNoticeProducer wakeup() {
            return mock(PayNoticeProducer.class);
        }

        @Bean
        PaymentEffects effects(
                MemberUserService users,
                UserBillService bills,
                StoreOrderStatusService statuses,
                AsyncStoreOrderService statistics,
                StoreOrderCartInfoService carts,
                StoreOrderMapper orders,
                WeixinNoticeProducer notifications) {
            return new PaymentEffects(
                    users, bills, statuses, statistics, carts, orders, notifications);
        }

        @Bean
        PaymentInbox inbox(PaymentMapper mapper) {
            return new PaymentInbox(mapper);
        }

        @Bean
        PaymentProcessor processor(
                PaymentMapper mapper, StoreOrderMapper orders, PaymentEffects effects) {
            return new PaymentProcessor(mapper, orders, effects);
        }

        @Bean
        PaymentFinalizationService service(
                PaymentInbox inbox,
                PaymentProcessor processor,
                PaymentMapper mapper,
                StoreOrderMapper orders,
                PayNoticeProducer wakeup) {
            return new PaymentFinalizationService(inbox, processor, mapper, orders, wakeup);
        }
    }

    @BeforeEach
    void setup() throws Exception {
        ctx = new AnnotationConfigApplicationContext(Config.class);
        jdbc = ctx.getBean(JdbcTemplate.class);
        service = ctx.getBean(PaymentFinalizationService.class);
        payments = ctx.getBean(PaymentMapper.class);
        orders = ctx.getBean(StoreOrderMapper.class);
        // Map every actual entity field, so production mapper/services, not substitute mappers,
        // execute.
        for (Class<?> entity :
                List.of(
                        StoreOrderDO.class,
                        MemberUserDO.class,
                        UserBillDO.class,
                        co.yixiang.yshop.module.order.dal.dataobject.storeorderstatus
                                .StoreOrderStatusDO.class)) createEntityTable(entity);
        jdbc.execute("CREATE TABLE test_inventory(id INT PRIMARY KEY, stock INT)");
        jdbc.update("INSERT INTO test_inventory VALUES(1,0)");
        jdbc.execute("CREATE TABLE test_coupon(id INT PRIMARY KEY, status INT)");
        jdbc.update("INSERT INTO test_coupon VALUES(1,1)");
        String migration =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of(
                                "../../sql/migrations/2026-10-03-payment-finalization.sql"));
        migration =
                migration
                        .replaceAll("(?m)^--.*$", "")
                        .replace("CHARACTER SET ascii COLLATE ascii_bin", "")
                        .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.update(
                "INSERT INTO yshop_user(id,pay_count,now_money,login_type,deleted)"
                        + " VALUES(1,0,100,'routine',0)");
        order("order-A", 1L);
        clearInvocations(ctx.getBean(WeixinNoticeProducer.class));
    }

    void createEntityTable(Class<?> type) {
        var table = TableInfoHelper.getTableInfo(type);
        List<String> columns = new ArrayList<>();
        columns.add(table.getKeyColumn() + " BIGINT PRIMARY KEY");
        for (var field : table.getFieldList()) {
            Class<?> t = field.getPropertyType();
            String sqlType =
                    t == BigDecimal.class
                            ? "DECIMAL(20,4)"
                            : t == LocalDateTime.class
                                    ? "TIMESTAMP"
                                    : (Number.class.isAssignableFrom(t)
                                            ? "BIGINT"
                                            : t == Boolean.class ? "BOOLEAN" : "VARCHAR(2048)");
            columns.add(
                    field.getColumn()
                            + " "
                            + sqlType
                            + (field.getColumn().equals("deleted") ? " DEFAULT 0" : ""));
        }
        jdbc.execute(
                "CREATE TABLE " + table.getTableName() + " (" + String.join(",", columns) + ")");
        if (table.getKeySequence() != null)
            jdbc.execute("CREATE SEQUENCE " + table.getKeySequence().value());
    }

    @AfterEach
    void close() {
        if (ctx != null) ctx.close();
    }

    void order(String id, long key) {
        jdbc.update(
                "INSERT INTO"
                    + " yshop_store_order(id,order_id,uid,pay_price,paid,status,refund_status,is_system_del,deleted,order_type,number_id,shop_name,coupon_id)"
                    + " VALUES(?,?,1,0.02,0,0,0,0,0,'takein',1,'Synthetic shop',1)",
                key,
                id);
    }

    PaymentSuccessEvent event(String order, String transaction, long cents) {
        return new PaymentSuccessEvent(
                PaymentSuccessEvent.Provider.WECHAT,
                "synthetic",
                order,
                transaction,
                cents,
                "synthetic-app",
                "synthetic-merchant",
                "SUCCESS",
                LocalDateTime.now());
    }

    PaymentResult pay() {
        return service.accept(event("order-A", "synthetic-T1", 2));
    }

    int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    void effects(int expected) {
        assertEquals(expected, count("SELECT pay_count FROM yshop_user WHERE id=1"));
        assertEquals(expected, count("SELECT COUNT(*) FROM yshop_user_bill"));
        assertEquals(
                expected,
                count(
                        "SELECT COUNT(*) FROM yshop_store_order_status WHERE"
                                + " change_type='pay_success'"));
        assertEquals(
                expected, count("SELECT COUNT(*) FROM yshop_order_payment WHERE status='SUCCESS'"));
        verify(ctx.getBean(WeixinNoticeProducer.class), times(expected))
                .sendNoticeMessage(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        anyString());
    }

    @Test
    void singleSuccess() {
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        effects(1);
        assertEquals(1, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(
                new BigDecimal("0.0200"),
                jdbc.queryForObject("SELECT number FROM yshop_user_bill", BigDecimal.class));
    }

    @Test
    void sequentialDuplicatesOneHundred() {
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        for (int i = 0; i < 100; i++) assertEquals(PaymentResult.IDEMPOTENT_DUPLICATE, pay());
        effects(1);
        assertEquals(100, count("SELECT duplicate_count FROM yshop_order_payment"));
    }

    @Test
    void twentyConcurrentTransactions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        var start = new CountDownLatch(1);
        try {
            List<Future<PaymentResult>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return pay();
                                }));
            start.countDown();
            Map<PaymentResult, Integer> totals = new EnumMap<>(PaymentResult.class);
            for (var result : results)
                totals.merge(result.get(40, TimeUnit.SECONDS), 1, Integer::sum);
            assertEquals(
                    Map.of(PaymentResult.FIRST_SUCCESS, 1, PaymentResult.IDEMPOTENT_DUPLICATE, 19),
                    totals);
            effects(1);
            assertEquals(1, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void wrongAmount(long amount) {
        assertEquals(
                PaymentResult.REJECTED, service.accept(event("order-A", "synthetic-T1", amount)));
        effects(0);
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(
                "PAYMENT_AMOUNT_MISMATCH",
                jdbc.queryForObject("SELECT status FROM yshop_order_payment", String.class));
    }

    @Test
    void unknownOrderFailsProviderButPersists() {
        assertEquals(
                PaymentResult.UNKNOWN_ORDER, service.accept(event("missing", "synthetic-T1", 2)));
        effects(0);
        assertEquals(
                "UNKNOWN_ORDER",
                jdbc.queryForObject("SELECT status FROM yshop_order_payment", String.class));
        assertFalse(PaymentResult.UNKNOWN_ORDER.acknowledge());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted=1", "is_system_del=1", "refund_status=1", "status=-2"})
    void canceledDeletedRefunded(String state) {
        jdbc.update("UPDATE yshop_store_order SET " + state + " WHERE id=1");
        assertEquals(PaymentResult.RECONCILIATION_REQUIRED, pay());
        effects(0);
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
    }

    @Test
    void transactionCannotPayAnotherOrder() {
        order("order-B", 2);
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        assertEquals(PaymentResult.REJECTED, service.accept(event("order-B", "synthetic-T1", 2)));
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=2"));
        effects(1);
        assertEquals(
                "TRANSACTION_ORDER_CONFLICT",
                jdbc.queryForObject(
                        "SELECT reason FROM yshop_order_payment_conflict", String.class));
    }

    @Test
    void secondTransactionIsNotDuplicate() {
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        assertEquals(
                PaymentResult.RECONCILIATION_REQUIRED,
                service.accept(event("order-A", "synthetic-T2", 2)));
        effects(1);
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM yshop_order_payment WHERE status='PAYMENT_CONFLICT'"));
    }

    @Test
    void sameTransactionDifferentPayloadIsRejected() {
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        assertEquals(PaymentResult.REJECTED, service.accept(event("order-A", "synthetic-T1", 3)));
        effects(1);
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_order_payment_conflict WHERE"
                                + " reason='PAYMENT_CONFLICT'"));
    }

    @Test
    void historicalPaidOrderIsNotGuessedDuplicate() {
        jdbc.update("UPDATE yshop_store_order SET paid=1 WHERE id=1");
        assertEquals(PaymentResult.RECONCILIATION_REQUIRED, pay());
        effects(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYCOUNT", "STATUS", "BILL", "AFTER_TRANSITION", "SUCCESS_RECORD"})
    void rollbackAndRecovery(String point) {
        if (point.equals("PAYCOUNT")) ctx.getBean(AtomicReference.class).set("PAYCOUNT");
        if (point.equals("STATUS"))
            jdbc.execute(
                    "ALTER TABLE yshop_store_order_status ADD CONSTRAINT injected_fault"
                            + " CHECK(change_type <> 'pay_success')");
        if (point.equals("SUCCESS_RECORD"))
            jdbc.execute(
                    "ALTER TABLE yshop_order_payment ADD CONSTRAINT injected_fault CHECK(status <>"
                            + " 'SUCCESS')");
        if (point.equals("BILL"))
            jdbc.execute(
                    "ALTER TABLE yshop_user_bill ADD CONSTRAINT injected_fault CHECK(number < 0)");
        if (point.equals("AFTER_TRANSITION"))
            doThrow(new IllegalStateException("SYNTHETIC_FAILURE"))
                    .when(ctx.getBean(MemberUserService.class))
                    .incPayCount(anyLong());
        assertEquals(PaymentResult.RETRY, pay());
        effects(0);
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(
                "FAILED_RETRYABLE",
                jdbc.queryForObject("SELECT status FROM yshop_order_payment", String.class));
        if (point.equals("STATUS"))
            jdbc.execute("ALTER TABLE yshop_store_order_status DROP CONSTRAINT injected_fault");
        if (point.equals("SUCCESS_RECORD"))
            jdbc.execute("ALTER TABLE yshop_order_payment DROP CONSTRAINT injected_fault");
        if (point.equals("BILL"))
            jdbc.execute("ALTER TABLE yshop_user_bill DROP CONSTRAINT injected_fault");
        ctx.getBean(AtomicReference.class).set("");
        if (point.equals("AFTER_TRANSITION"))
            doAnswer(
                            call -> {
                                ctx.getBean(MemberUserMapper.class)
                                        .incPayCount(call.getArgument(0));
                                return null;
                            })
                    .when(ctx.getBean(MemberUserService.class))
                    .incPayCount(anyLong());
        service.recover();
        effects(1);
        assertEquals(PaymentResult.IDEMPOTENT_DUPLICATE, pay());
        effects(1);
    }

    @Test
    void notificationFailureDoesNotRollbackPaymentOrRepeat() {
        doThrow(new IllegalStateException("SYNTHETIC_TRANSPORT_FAILURE"))
                .when(ctx.getBean(WeixinNoticeProducer.class))
                .sendNoticeMessage(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        anyString());
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        assertEquals(PaymentResult.IDEMPOTENT_DUPLICATE, pay());
        effects(1);
    }

    @Test
    void redisOutageDoesNotLoseReceipt() {
        doThrow(new IllegalStateException("SYNTHETIC_REDIS_FAILURE"))
                .when(ctx.getBean(PayNoticeProducer.class))
                .sendPayNoticeMessage(anyString());
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        effects(1);
    }

    @Test
    void rechargeIsDurablyUnsupportedNotCredited() {
        jdbc.update(
                "INSERT INTO yshop_user_bill(id,uid,extend_field,status,number)"
                        + " VALUES(99,1,'recharge-A',0,10)");
        for (int i = 0; i < 10; i++)
            assertEquals(
                    PaymentResult.REJECTED,
                    service.accept(event("recharge-A", "synthetic-T1", 1000)));
        assertEquals(0, count("SELECT pay_count FROM yshop_user WHERE id=1"));
        assertEquals(0, count("SELECT status FROM yshop_user_bill WHERE id=99"));
        verify(ctx.getBean(MemberUserService.class), never()).incMoney(anyLong(), any());
        assertEquals(
                "UNSUPPORTED_RECHARGE",
                jdbc.queryForObject("SELECT status FROM yshop_order_payment", String.class));
    }

    @Test
    void databaseConstraintsAreLastDefense() {
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO yshop_order_payment SELECT "
                                    + "'other',order_id,provider,merchant_details_id,out_trade_no,provider_transaction_id,amount_cents,appid,mch_id,result_code,status,failure_reason,success_order_id,received_at,last_seen_at,duplicate_count,processed_at,create_time,update_time"
                                    + " FROM yshop_order_payment"));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO yshop_order_payment SELECT "
                                    + "'other',order_id,provider,merchant_details_id,out_trade_no,'synthetic-T2',amount_cents,appid,mch_id,result_code,status,failure_reason,success_order_id,received_at,last_seen_at,duplicate_count,processed_at,create_time,update_time"
                                    + " FROM yshop_order_payment"));
    }

    @Test
    void conditionalUpdateOnlyFirstOwnsEffects() {
        assertEquals(1, orders.markPaid(1L, "cash"));
        assertEquals(0, orders.markPaid(1L, "cash"));
    }

    @Test
    void internalProvidersAreNotExternalTransactions() {
        assertEquals(
                PaymentResult.FIRST_SUCCESS,
                service.finalizeInternal("order-A", PaymentSuccessEvent.Provider.CASH));
        assertEquals(
                PaymentResult.IDEMPOTENT_DUPLICATE,
                service.finalizeInternal("order-A", PaymentSuccessEvent.Provider.CASH));
        effects(1);
        assertEquals(
                "CASH",
                jdbc.queryForObject("SELECT provider FROM yshop_order_payment", String.class));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.processPending(
                                jdbc.queryForObject(
                                        "SELECT id FROM yshop_order_payment", String.class)));
    }

    PayNoticeConsumer consumer() {
        var consumer = new PayNoticeConsumer();
        ReflectionTestUtils.setField(consumer, "paymentFinalizationService", service);
        return consumer;
    }

    @Test
    void redeliveredMessageDoesNotRepeatEffects() {
        pay();
        var message =
                new PayNoticeMessage()
                        .setEventId(
                                jdbc.queryForObject(
                                        "SELECT id FROM yshop_order_payment", String.class));
        for (int i = 0; i < 10; i++) consumer().onMessage(message);
        effects(1);
    }

    @Test
    void streamFailureIsNotAcknowledgedAndRetryWorks() {
        var receipt = ctx.getBean(PaymentInbox.class).capture(event("order-A", "synthetic-T1", 2));
        var consumer = consumer();
        var mq = mock(RedisMQTemplate.class);
        var redis = mock(StringRedisTemplate.class);
        var stream = mock(StreamOperations.class);
        doReturn(redis).when(mq).getRedisTemplate();
        when(redis.opsForStream()).thenReturn(stream);
        when(mq.getInterceptors()).thenReturn(List.of());
        consumer.setRedisMQTemplate(mq);
        var record =
                ObjectRecord.create("order.pay.notice", "{\"eventId\":\"" + receipt.id() + "\"}")
                        .withId(
                                org.springframework.data.redis.connection.stream.RecordId.of(
                                        "1-0"));
        ctx.getBean(AtomicReference.class).set("PAYCOUNT");
        assertThrows(RuntimeException.class, () -> consumer.onMessage(record));
        verify(stream, never()).acknowledge(any(), any(ObjectRecord.class));
        effects(0);
        ctx.getBean(AtomicReference.class).set("");
        consumer.onMessage(record);
        verify(stream, times(1)).acknowledge(any(), any(ObjectRecord.class));
        effects(1);
        consumer.onMessage(record);
        effects(1);
    }

    @Test
    void noticeIsEmittedOnlyAfterVisibleDatabaseCommit() {
        doAnswer(
                        call -> {
                            // Separate connection, not the transaction-bound MyBatis/JDBC
                            // connection.
                            try (var connection = ctx.getBean(DataSource.class).getConnection();
                                    var statement = connection.createStatement();
                                    var result =
                                            statement.executeQuery(
                                                    "SELECT paid FROM yshop_store_order WHERE"
                                                        + " id=1")) {
                                assertTrue(result.next());
                                assertEquals(1, result.getInt(1));
                            }
                            return null;
                        })
                .when(ctx.getBean(WeixinNoticeProducer.class))
                .sendNoticeMessage(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        anyString());
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        effects(1);
    }

    AppStoreOrderServiceImpl balanceService() {
        var target = spy(new AppStoreOrderServiceImpl());
        ReflectionTestUtils.setField(
                target, "transactionManager", ctx.getBean(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(target, "paymentFinalizationService", service);
        ReflectionTestUtils.setField(target, "userService", ctx.getBean(MemberUserService.class));
        doAnswer(
                        call -> {
                            var vo = new AppStoreOrderQueryVo();
                            vo.setId(1L);
                            vo.setOrderId("order-A");
                            vo.setUid(1L);
                            vo.setPaid(count("SELECT paid FROM yshop_store_order WHERE id=1"));
                            vo.setPayPrice(new BigDecimal("0.02"));
                            return vo;
                        })
                .when(target)
                .getOrderInfo(anyString(), nullable(Long.class));
        var users = ctx.getBean(MemberUserService.class);
        when(users.getAppUser(anyLong()))
                .thenAnswer(
                        call -> {
                            var user =
                                    new co.yixiang.yshop.module.member.controller.app.user.vo
                                            .AppUserQueryVo();
                            user.setNowMoney(
                                    jdbc.queryForObject(
                                            "SELECT now_money FROM yshop_user WHERE id=1",
                                            BigDecimal.class));
                            return user;
                        });
        doAnswer(
                        call -> {
                            ctx.getBean(MemberUserMapper.class)
                                    .decPrice(call.getArgument(1), call.getArgument(0));
                            return null;
                        })
                .when(users)
                .decPrice(anyLong(), any(BigDecimal.class));
        return target; // Deliberately no proxy: proves pay()->this.yuePay() uses an actual
                       // transaction.
    }

    @Test
    void balanceAdapterRollsBackDebitOnFinalizationFailure() {
        var balance = balanceService();
        jdbc.execute("ALTER TABLE yshop_user_bill ADD CONSTRAINT injected_fault CHECK(number < 0)");
        assertThrows(RuntimeException.class, () -> balance.yuePay("order-A", 1L));
        assertEquals(
                new BigDecimal("100.0000"),
                jdbc.queryForObject(
                        "SELECT now_money FROM yshop_user WHERE id=1", BigDecimal.class));
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        effects(0);
        service.recover(); // Internal receipt must never credit an order without a matching debit.
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        jdbc.execute("ALTER TABLE yshop_user_bill DROP CONSTRAINT injected_fault");
        balance.yuePay("order-A", 1L);
        effects(1);
        assertEquals(
                new BigDecimal("99.9800"),
                jdbc.queryForObject(
                        "SELECT now_money FROM yshop_user WHERE id=1", BigDecimal.class));
        assertThrows(
                co.yixiang.yshop.framework.common.exception.ServiceException.class,
                () -> balance.yuePay("order-A", 1L));
        assertEquals(
                new BigDecimal("99.9800"),
                jdbc.queryForObject(
                        "SELECT now_money FROM yshop_user WHERE id=1", BigDecimal.class));
        effects(1);
    }

    AppStoreOrderService cancelService() {
        var target = spy(new AppStoreOrderServiceImpl());
        ReflectionTestUtils.setField(target, "storeOrderMapper", orders);
        ReflectionTestUtils.setField(
                target, "storeOrderCartInfoService", ctx.getBean(StoreOrderCartInfoService.class));
        doAnswer(
                        call ->
                                jdbc
                                        .query(
                                                "SELECT id,uid,paid,status,coupon_id,order_id FROM"
                                                        + " yshop_store_order WHERE order_id=? AND"
                                                        + " deleted=0",
                                                (rs, n) -> {
                                                    var vo = new AppStoreOrderQueryVo();
                                                    vo.setId(rs.getLong(1));
                                                    vo.setUid(rs.getLong(2));
                                                    vo.setPaid(rs.getInt(3));
                                                    vo.setStatus(rs.getInt(4));
                                                    vo.setCouponId(rs.getInt(5));
                                                    vo.setOrderId(rs.getString(6));
                                                    return vo;
                                                },
                                                (String) call.getArgument(0))
                                        .stream()
                                        .findFirst()
                                        .orElse(null))
                .when(target)
                .getOrderInfo(anyString(), nullable(Long.class));
        var products = mock(AppStoreProductService.class);
        doAnswer(
                        call -> {
                            jdbc.update("UPDATE test_inventory SET stock=stock+1 WHERE id=1");
                            return null;
                        })
                .when(products)
                .incProductStock(
                        anyInt(), anyLong(), anyString(), anyLong(), nullable(String.class));
        ReflectionTestUtils.setField(target, "appStoreProductService", products);
        var coupons = mock(AppCouponUserService.class);
        when(coupons.getOne(any()))
                .thenAnswer(
                        call -> {
                            var coupon = new CouponUserDO();
                            coupon.setId(1);
                            coupon.setStatus(1);
                            return coupon;
                        });
        when(coupons.updateById(any()))
                .thenAnswer(
                        call -> {
                            jdbc.update("UPDATE test_coupon SET status=0 WHERE id=1");
                            return true;
                        });
        ReflectionTestUtils.setField(target, "appCouponUserService", coupons);
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(
                new TransactionInterceptor(
                        ctx.getBean(PlatformTransactionManager.class),
                        new AnnotationTransactionAttributeSource()));
        return (AppStoreOrderService) factory.getProxy();
    }

    @Test
    void cancellationWinsAndLatePaymentRequiresReconciliation() {
        cancelService().cancelOrder("order-A", 1L);
        assertEquals(PaymentResult.RECONCILIATION_REQUIRED, pay());
        effects(0);
        assertEquals(1, count("SELECT stock FROM test_inventory"));
        assertEquals(0, count("SELECT status FROM test_coupon"));
    }

    @Test
    void cancellationRestorationFailureRollsBackDeletionAndInventory() {
        var cancel = cancelService();
        jdbc.execute(
                "ALTER TABLE test_coupon ADD CONSTRAINT injected_coupon_fault CHECK(status=1)");
        assertThrows(RuntimeException.class, () -> cancel.cancelOrder("order-A", 1L));
        assertEquals(0, count("SELECT stock FROM test_inventory"));
        assertEquals(0, count("SELECT deleted FROM yshop_store_order WHERE id=1"));
        assertEquals(1, count("SELECT status FROM test_coupon"));
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        effects(1);
    }

    @Test
    void paymentWinsNoInventoryOrCouponRestore() {
        assertEquals(PaymentResult.FIRST_SUCCESS, pay());
        assertThrows(
                co.yixiang.yshop.framework.common.exception.ServiceException.class,
                () -> cancelService().cancelOrder("order-A", 1L));
        effects(1);
        assertEquals(0, count("SELECT stock FROM test_inventory"));
        assertEquals(1, count("SELECT status FROM test_coupon"));
    }

    @RepeatedTest(20)
    void realCancellationPaymentRace() throws Exception {
        var cancel = cancelService();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var cancellation =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    cancel.cancelOrder("order-A", 1L);
                                    return true;
                                } catch (
                                        co.yixiang.yshop.framework.common.exception.ServiceException
                                                ex) {
                                    return false;
                                }
                            });
            var payment =
                    pool.submit(
                            () -> {
                                start.await();
                                return pay();
                            });
            start.countDown();
            boolean canceled = cancellation.get(30, TimeUnit.SECONDS);
            PaymentResult result = payment.get(30, TimeUnit.SECONDS);
            if (canceled) {
                assertEquals(PaymentResult.RECONCILIATION_REQUIRED, result);
                effects(0);
                assertEquals(1, count("SELECT stock FROM test_inventory"));
                assertEquals(0, count("SELECT status FROM test_coupon"));
            } else {
                assertEquals(PaymentResult.FIRST_SUCCESS, result);
                effects(1);
                assertEquals(0, count("SELECT stock FROM test_inventory"));
                assertEquals(1, count("SELECT status FROM test_coupon"));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
