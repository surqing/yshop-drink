package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.framework.common.exception.ServiceException;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.dal.mysql.wallet.*;
import co.yixiang.yshop.module.member.service.user.MemberUserService;
import co.yixiang.yshop.module.member.service.userbill.UserBillService;
import co.yixiang.yshop.module.member.service.wallet.*;
import co.yixiang.yshop.module.order.dal.dataobject.storeordercartinfo.StoreOrderCartInfoDO;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.dal.mysql.storeordercartinfo.StoreOrderCartInfoMapper;
import co.yixiang.yshop.module.order.service.storeorder.*;
import co.yixiang.yshop.module.order.service.storeorderstatus.StoreOrderStatusService;
import co.yixiang.yshop.module.pay.callback.*;
import co.yixiang.yshop.module.product.service.storeproduct.AppStoreProductService;

import com.egzosn.pay.spring.boot.core.PayServiceManager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

class WalletDatabaseTest {
    PaymentDatabaseTest f;
    WalletService wallet;
    RechargeService recharge;
    PayServiceManager refundProvider;
    boolean syntheticProviderQuery;

    @BeforeEach
    void setup() throws Exception {
        f = new PaymentDatabaseTest();
        f.setup();
        wallet = f.ctx.getBean(WalletService.class);
        recharge = f.ctx.getBean(RechargeService.class);
    }

    @AfterEach
    void close() {
        if (refundProvider != null && !syntheticProviderQuery) verifyNoInteractions(refundProvider);
        if (f != null) f.close();
    }

    BigDecimal balance() {
        return f.jdbc.queryForObject(
                "SELECT now_money FROM yshop_user WHERE id=1", BigDecimal.class);
    }

    void same(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual));
    }

    void reconcile() {
        same(balance(), f.ctx.getBean(WalletMapper.class).sum(1L));
    }

    int count(String sql) {
        return f.count(sql);
    }

    List<Boolean> concurrent(int count, java.util.function.IntFunction<Boolean> task)
            throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var gate = new CountDownLatch(1);
        var ready = new CountDownLatch(count);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int n = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(gate.await(30, TimeUnit.SECONDS));
                                    return task.apply(n);
                                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "All workers must be ready before release");
            gate.countDown();
            List<Boolean> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    StoreOrderService refundService(boolean stockFailure) {
        return refundService(stockFailure, f.orders);
    }

    StoreOrderService refundService(boolean stockFailure, StoreOrderMapper orders) {
        var target = new StoreOrderServiceImpl();
        ReflectionTestUtils.setField(target, "storeOrderMapper", orders);
        ReflectionTestUtils.setField(target, "walletService", wallet);
        var users = f.ctx.getBean(MemberUserService.class);
        when(users.getById(anyLong()))
                .thenAnswer(
                        call ->
                                f.ctx.getBean(MemberUserMapper.class)
                                        .selectById((Long) call.getArgument(0)));
        ReflectionTestUtils.setField(target, "userService", users);
        ReflectionTestUtils.setField(target, "billService", f.ctx.getBean(UserBillService.class));
        ReflectionTestUtils.setField(
                target, "storeOrderStatusService", f.ctx.getBean(StoreOrderStatusService.class));
        var carts = mock(StoreOrderCartInfoMapper.class);
        var item = new StoreOrderCartInfoDO();
        item.setNumber(1);
        item.setProductId(1L);
        item.setSpec("synthetic");
        when(carts.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(item));
        ReflectionTestUtils.setField(target, "storeOrderCartInfoMapper", carts);
        var products = mock(AppStoreProductService.class);
        doAnswer(
                        call -> {
                            // Transaction-bound SQL, as in the cancellation acceptance fixture.
                            // Throw AFTER
                            // the stock write to prove that the outer refund transaction also rolls
                            // it back.
                            f.jdbc.update("UPDATE test_inventory SET stock=stock+1 WHERE id=1");
                            if (stockFailure)
                                throw new IllegalStateException("SYNTHETIC_REFUND_STOCK_FAILURE");
                            return null;
                        })
                .when(products)
                .incProductStock(
                        anyInt(), anyLong(), anyString(), anyLong(), nullable(String.class));
        ReflectionTestUtils.setField(target, "appStoreProductService", products);
        refundProvider = mock(PayServiceManager.class);
        ReflectionTestUtils.setField(target, "manager", refundProvider);
        ReflectionTestUtils.setField(
                target, "isDemo", false); // Provider transport is always mocked.
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(
                new TransactionInterceptor(
                        f.ctx.getBean(PlatformTransactionManager.class),
                        new AnnotationTransactionAttributeSource()));
        return (StoreOrderService) factory.getProxy();
    }

    void refund(StoreOrderService refunds) {
        refunds.orderRefund(1L, new BigDecimal("0.02"), 0, null);
    }

    void refundEffects(int expected) {
        assertEquals(
                expected,
                count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                            + " type='ORDER_REFUND'"));
        assertEquals(
                expected,
                count("SELECT COUNT(*) FROM yshop_user_bill WHERE type='pay_product_refund'"));
        assertEquals(
                expected,
                count(
                        "SELECT COUNT(*) FROM yshop_store_order_status WHERE"
                                + " change_type='refund_price_success'"));
        assertEquals(expected, count("SELECT stock FROM test_inventory WHERE id=1"));
    }

    @Test
    void syntheticWeChatQueryDoesNotHoldOrderLock() throws Exception {
        f.jdbc.update(
                "UPDATE yshop_store_order SET"
                    + " paid=1,pay_type='weixin',out_trade_no='synthetic-refund' WHERE id=1");
        var refunds = refundService(false);
        syntheticProviderQuery = true;
        when(refundProvider.refundQuery(anyString(), any()))
                .thenAnswer(
                        call -> {
                            // No network or real refund. An independent connection must be able
                            // to lock the order while the fake provider is queried. A shared
                            // FOR UPDATE would time out.
                            try (var connection =
                                            f.ctx.getBean(javax.sql.DataSource.class)
                                                    .getConnection();
                                    var statement = connection.createStatement()) {
                                connection.setAutoCommit(false);
                                statement.execute(
                                        PaymentDatabaseTest.mysqlAcceptance()
                                                ? "SET SESSION innodb_lock_wait_timeout=1"
                                                : "SET LOCK_TIMEOUT 1000");
                                try (var rows =
                                        statement.executeQuery(
                                                "SELECT id FROM yshop_store_order WHERE id=1 FOR"
                                                    + " UPDATE")) {
                                    assertTrue(rows.next());
                                }
                                connection.rollback();
                            }
                            return Map.of("'return_code'", "SUCCESS");
                        });
        refund(refunds);
        verify(refundProvider).refundQuery(anyString(), any());
        verifyNoMoreInteractions(refundProvider);
        same(new BigDecimal("100"), balance());
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", "refund", "pay-type", "price"})
    void balanceRefundValidatesLockedRowAfterOrdinaryRead(String changed) {
        f.balanceService().yuePay("order-A", 1L);
        var orders = spy(f.orders);
        doAnswer(
                        call -> {
                            var earlier = f.orders.selectById(1L);
                            String update =
                                    switch (changed) {
                                        case "paid" -> "paid=0";
                                        case "refund" -> "refund_status=2";
                                        case "pay-type" -> "pay_type='weixin'";
                                        default -> "pay_price=0.01";
                                    };
                            // Commit on a different connection after the ordinary read creates
                            // a snapshot. The locking read must observe this change even at
                            // MySQL REPEATABLE READ.
                            CompletableFuture.runAsync(
                                            () ->
                                                    f.jdbc.update(
                                                            "UPDATE yshop_store_order SET "
                                                                    + update
                                                                    + " WHERE id=1"))
                                    .get(10, TimeUnit.SECONDS);
                            return earlier;
                        })
                .when(orders)
                .selectById(1L);
        var refunds = refundService(false, orders);
        assertThrows(ServiceException.class, () -> refund(refunds));
        same(new BigDecimal("99.98"), balance());
        assertEquals(2, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        refundEffects(0);
        reconcile();
    }

    @Test
    void unpaidBalanceRefundIsRejectedWithoutLedger() {
        f.jdbc.update("UPDATE yshop_store_order SET pay_type='yue' WHERE id=1");
        var refunds = refundService(false);
        var ex = assertThrows(ServiceException.class, () -> refund(refunds));
        assertEquals(1008007017, ex.getCode());
        same(new BigDecimal("100"), balance());
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(0, count("SELECT refund_status FROM yshop_store_order WHERE id=1"));
        refundEffects(0);
    }

    @Test
    void missingBalanceRefundOrderIsRejected() {
        var refunds = refundService(false);
        var ex =
                assertThrows(
                        ServiceException.class,
                        () -> refunds.orderRefund(999L, new BigDecimal("0.02"), 0, null));
        assertEquals(1008007000, ex.getCode());
        same(new BigDecimal("100"), balance());
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        refundEffects(0);
    }

    @Test
    void sameBalanceOrderRefundTwentyCreditsOnce() throws Exception {
        f.balanceService().yuePay("order-A", 1L);
        var refunds = refundService(false);
        var results =
                concurrent(
                        20,
                        n -> {
                            try {
                                refund(refunds);
                                return true;
                            } catch (ServiceException duplicate) {
                                assertEquals(1008007021, duplicate.getCode());
                                return false;
                            }
                        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        same(new BigDecimal("100"), balance());
        assertEquals(1, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(2, count("SELECT refund_status FROM yshop_store_order WHERE id=1"));
        same(
                new BigDecimal("0.02"),
                f.jdbc.queryForObject(
                        "SELECT refund_price FROM yshop_store_order WHERE id=1", BigDecimal.class));
        refundEffects(1);
        reconcile();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void balancePayAndRefundRaceHasConsistentState(int repetition) throws Exception {
        // Every repetition uses a fresh isolated schema/fixture, for both H2 and MySQL.
        f.jdbc.update("UPDATE yshop_store_order SET pay_type='yue' WHERE id=1");
        var pay = f.balanceService();
        var refunds = refundService(false);
        var results =
                concurrent(
                        2,
                        n -> {
                            if (n == 0) {
                                pay.yuePay("order-A", 1L);
                                return true;
                            }
                            try {
                                refund(refunds);
                                return true;
                            } catch (ServiceException unpaid) {
                                assertEquals(1008007017, unpaid.getCode());
                                return false;
                            }
                        });
        assertTrue(results.get(0));
        int refunded = results.get(1) ? 1 : 0;
        same(new BigDecimal(refunded == 1 ? "100" : "99.98"), balance());
        assertEquals(1, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(0, count("SELECT status FROM yshop_store_order WHERE id=1"));
        assertEquals(refunded * 2, count("SELECT refund_status FROM yshop_store_order WHERE id=1"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                            + " type='ORDER_PAYMENT'"));
        assertEquals(1, count("SELECT COUNT(*) FROM yshop_order_payment WHERE status='SUCCESS'"));
        assertEquals(1, count("SELECT pay_count FROM yshop_user WHERE id=1"));
        assertEquals(1 + refunded, count("SELECT COUNT(*) FROM yshop_user_bill"));
        refundEffects(refunded);
        reconcile();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFUND_STATUS", "BILL", "STATUS", "STOCK"})
    void balanceRefundPostCreditFailureRollsBackEverything(String point) {
        f.balanceService().yuePay("order-A", 1L);
        if (point.equals("REFUND_STATUS"))
            f.jdbc.execute(
                    "ALTER TABLE yshop_store_order ADD CONSTRAINT injected_fault"
                        + " CHECK(refund_status<>2)");
        if (point.equals("BILL"))
            f.jdbc.execute("ALTER TABLE yshop_user_bill ADD CONSTRAINT injected_fault CHECK(pm=0)");
        if (point.equals("STATUS"))
            f.jdbc.execute(
                    "ALTER TABLE yshop_store_order_status ADD CONSTRAINT injected_fault"
                            + " CHECK(change_type<>'refund_price_success')");
        var refunds = refundService(point.equals("STOCK"));
        assertThrows(RuntimeException.class, () -> refund(refunds));
        same(new BigDecimal("99.98"), balance());
        assertEquals(2, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        assertEquals(0, count("SELECT refund_status FROM yshop_store_order WHERE id=1"));
        assertEquals(1, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(0, count("SELECT status FROM yshop_store_order WHERE id=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM yshop_user_bill"));
        assertEquals(1, count("SELECT COUNT(*) FROM yshop_store_order_status"));
        refundEffects(0);
        reconcile();
        if (!point.equals("STOCK"))
            f.dropFault(
                    point.equals("REFUND_STATUS")
                            ? "yshop_store_order"
                            : point.equals("BILL")
                                    ? "yshop_user_bill"
                                    : "yshop_store_order_status");
        refund(refundService(false));
        same(new BigDecimal("100"), balance());
        assertEquals(2, count("SELECT refund_status FROM yshop_store_order WHERE id=1"));
        refundEffects(1);
        reconcile();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-0.01", "0.03", "0.001"})
    void balanceRefundRejectsInvalidAmounts(String value) {
        f.balanceService().yuePay("order-A", 1L);
        var refunds = refundService(false);
        assertThrows(
                RuntimeException.class,
                () ->
                        refunds.orderRefund(
                                1L, value.equals("null") ? null : new BigDecimal(value), 0, null));
        same(new BigDecimal("99.98"), balance());
        assertEquals(2, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        assertEquals(0, count("SELECT refund_status FROM yshop_store_order WHERE id=1"));
        refundEffects(0);
        reconcile();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "refunded",
                "invalid-refund",
                "finished-refund-status",
                "invalid-status",
                "deleted",
                "system-deleted"
            })
    void balanceRefundRejectsIneligibleStates(String state) {
        f.balanceService().yuePay("order-A", 1L);
        String change =
                switch (state) {
                    case "refunded" -> "refund_status=2";
                    case "invalid-refund" -> "refund_status=3";
                    case "finished-refund-status" -> "status=-2";
                    case "invalid-status" -> "status=4";
                    case "deleted" -> "deleted=1";
                    default -> "is_system_del=1";
                };
        f.jdbc.update("UPDATE yshop_store_order SET " + change + " WHERE id=1");
        var refunds = refundService(false);
        assertThrows(ServiceException.class, () -> refund(refunds));
        same(new BigDecimal("99.98"), balance());
        assertEquals(2, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        refundEffects(0);
        reconcile();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, -1})
    void balanceRefundAcceptsExistingPaidLifecycleStates(int status) {
        f.balanceService().yuePay("order-A", 1L);
        f.jdbc.update("UPDATE yshop_store_order SET status=?,refund_status=1 WHERE id=1", status);
        refund(refundService(false));
        same(new BigDecimal("100"), balance());
        assertEquals(status, count("SELECT status FROM yshop_store_order WHERE id=1"));
        assertEquals(2, count("SELECT refund_status FROM yshop_store_order WHERE id=1"));
        refundEffects(1);
        reconcile();
    }

    @Test
    void sameOrderBalancePayTwenty() throws Exception {
        wallet.open(1L);
        var adapter = f.balanceService();
        var results =
                concurrent(
                        20,
                        n -> {
                            try {
                                adapter.yuePay("order-A", 1L);
                                return true;
                            } catch (
                                    co.yixiang.yshop.framework.common.exception.ServiceException
                                            duplicate) {
                                return false;
                            }
                        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        same(new BigDecimal("99.98"), balance());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                            + " type='ORDER_PAYMENT'"));
        f.effects(1);
        reconcile();
    }

    @Test
    void threeOrdersCannotOverspendFiveCents() throws Exception {
        f.jdbc.update("UPDATE yshop_user SET now_money=0.05 WHERE id=1");
        wallet.open(1L);
        f.order("order-B", 2);
        f.order("order-C", 3);
        var adapter = f.balanceService();
        var results =
                concurrent(
                        3,
                        n -> {
                            try {
                                adapter.yuePay(List.of("order-A", "order-B", "order-C").get(n), 1L);
                                return true;
                            } catch (IllegalStateException insufficient) {
                                assertEquals(
                                        "INSUFFICIENT_WALLET_BALANCE", insufficient.getMessage());
                                return false;
                            }
                        });
        assertEquals(2, results.stream().filter(Boolean::booleanValue).count());
        same(new BigDecimal("0.01"), balance());
        assertEquals(2, count("SELECT COUNT(*) FROM yshop_store_order WHERE paid=1"));
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                            + " type='ORDER_PAYMENT'"));
        f.effects(2);
        reconcile();
    }

    @Test
    void insufficientLeavesNoLedgerOrPaid() {
        f.jdbc.update("UPDATE yshop_user SET now_money=0.01 WHERE id=1");
        assertThrows(IllegalStateException.class, () -> f.balanceService().yuePay("order-A", 1L));
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        same(new BigDecimal("0.01"), balance());
    }

    @Test
    void duplicateIdempotency() {
        var a =
                wallet.debit(
                        1L, new BigDecimal("1"), WalletType.ADMIN_ADJUSTMENT, "adjust-A", "key-A");
        var b =
                wallet.debit(
                        1L,
                        new BigDecimal("1.00"),
                        WalletType.ADMIN_ADJUSTMENT,
                        "adjust-A",
                        "key-A");
        assertTrue(a.first());
        assertFalse(b.first());
        assertEquals(a.transaction().getId(), b.transaction().getId());
        same(new BigDecimal("99"), balance());
        assertEquals(2, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        reconcile();
    }

    @Test
    void duplicateWalletCreditTwenty() throws Exception {
        wallet.open(1L);
        var results =
                concurrent(
                        20,
                        n ->
                                wallet.credit(
                                                1L,
                                                new BigDecimal("1"),
                                                WalletType.ADMIN_ADJUSTMENT,
                                                "adjust-A",
                                                "key-A")
                                        .first());
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        same(new BigDecimal("101"), balance());
        reconcile();
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "direction", "business", "type", "user"})
    void idempotencyConflict(String change) {
        wallet.debit(1L, new BigDecimal("1"), WalletType.ADMIN_ADJUSTMENT, "A", "K");
        f.jdbc.update("INSERT INTO yshop_user(id,now_money,deleted)VALUES(2,100,0)");
        assertThrows(
                IllegalStateException.class,
                () -> {
                    if (change.equals("direction"))
                        wallet.credit(
                                1L, new BigDecimal("1"), WalletType.ADMIN_ADJUSTMENT, "A", "K");
                    else
                        wallet.debit(
                                change.equals("user") ? 2L : 1L,
                                new BigDecimal(change.equals("amount") ? "2" : "1"),
                                change.equals("type")
                                        ? WalletType.ORDER_PAYMENT
                                        : WalletType.ADMIN_ADJUSTMENT,
                                change.equals("business") ? "B" : "A",
                                "K");
                });
        same(new BigDecimal("99"), balance());
        assertEquals(2, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        reconcile();
    }

    @Test
    void debitThenExceptionRollsBack() {
        var tx = new TransactionTemplate(f.ctx.getBean(PlatformTransactionManager.class));
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.executeWithoutResult(
                                s -> {
                                    wallet.debit(
                                            1L,
                                            new BigDecimal("2"),
                                            WalletType.ADMIN_ADJUSTMENT,
                                            "A",
                                            "K");
                                    throw new IllegalStateException("SYNTHETIC_FAILURE");
                                }));
        same(new BigDecimal("100"), balance());
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
    }

    @Test
    void ledgerInsertFailureRollsBackBalance() {
        f.jdbc.execute(
                "ALTER TABLE yshop_member_wallet_transaction ADD CONSTRAINT injected_ledger"
                    + " CHECK(type='OPENING_BALANCE')");
        assertThrows(
                RuntimeException.class,
                () -> wallet.debit(1L, new BigDecimal("2"), WalletType.ADMIN_ADJUSTMENT, "A", "K"));
        same(new BigDecimal("100"), balance());
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
    }

    @Test
    void finalizationFailureRollsBackWallet() {
        f.jdbc.execute("ALTER TABLE yshop_user_bill ADD CONSTRAINT injected_fault CHECK(number<0)");
        assertThrows(RuntimeException.class, () -> f.balanceService().yuePay("order-A", 1L));
        same(new BigDecimal("100"), balance());
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        f.effects(0);
        f.service.recover();
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
        f.dropFault("yshop_user_bill");
        f.balanceService().yuePay("order-A", 1L);
        f.effects(1);
        reconcile();
    }

    @Test
    void balanceFinalizationRequiresDebit() {
        assertThrows(
                IllegalStateException.class,
                () -> f.service.finalizeInternal("order-A", PaymentSuccessEvent.Provider.BALANCE));
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=1"));
    }

    @Test
    void rechargeCompletionTwenty() throws Exception {
        wallet.open(1L);
        recharge.createSynthetic(1L, "recharge-A", new BigDecimal("1"), new BigDecimal("0.2"));
        var results =
                concurrent(
                        20,
                        n ->
                                recharge.completeSynthetic(
                                        "recharge-A", "synthetic-R1", new BigDecimal("1")));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        same(new BigDecimal("101.20"), balance());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                            + " type='RECHARGE'"));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM yshop_member_recharge_order WHERE status='SUCCESS'"));
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_user_bill"));
        reconcile();
    }

    @Test
    void rechargeTransactionCannotCreditTwoOrders() {
        recharge.createSynthetic(1L, "R1", new BigDecimal("1"), BigDecimal.ZERO);
        recharge.createSynthetic(1L, "R2", new BigDecimal("1"), BigDecimal.ZERO);
        recharge.completeSynthetic("R1", "T1", new BigDecimal("1"));
        assertThrows(
                RuntimeException.class,
                () -> recharge.completeSynthetic("R2", "T1", new BigDecimal("1")));
        same(new BigDecimal("101"), balance());
        assertEquals(
                "CREATED",
                f.jdbc.queryForObject(
                        "SELECT status FROM yshop_member_recharge_order WHERE recharge_no='R2'",
                        String.class));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                            + " type='RECHARGE'"));
        reconcile();
    }

    @Test
    void rechargeWrongAmountAndTransaction() {
        recharge.createSynthetic(1L, "R1", new BigDecimal("1"), BigDecimal.ZERO);
        assertThrows(
                IllegalStateException.class,
                () -> recharge.completeSynthetic("R1", "T1", new BigDecimal("2")));
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        recharge.completeSynthetic("R1", "T1", new BigDecimal("1"));
        assertThrows(
                IllegalStateException.class,
                () -> recharge.completeSynthetic("R1", "T2", new BigDecimal("1")));
        reconcile();
    }

    @Test
    void rechargeStatusFailureRollsBackCredit() {
        recharge.createSynthetic(1L, "R1", new BigDecimal("1"), BigDecimal.ZERO);
        f.jdbc.execute(
                "ALTER TABLE yshop_member_recharge_order ADD CONSTRAINT injected_recharge"
                    + " CHECK(status='CREATED')");
        assertThrows(
                RuntimeException.class,
                () -> recharge.completeSynthetic("R1", "T1", new BigDecimal("1")));
        same(new BigDecimal("100"), balance());
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
    }

    @Test
    void syntheticDisabled() {
        var disabled = new RechargeService(f.ctx.getBean(RechargeMapper.class), wallet, false);
        assertThrows(
                IllegalStateException.class,
                () -> disabled.createSynthetic(1L, "R1", new BigDecimal("1"), BigDecimal.ZERO));
        assertThrows(
                IllegalStateException.class,
                () -> disabled.completeSynthetic("R1", "T1", new BigDecimal("1")));
    }

    @Test
    void openingRerunNeverChangesMoney() {
        var m = f.ctx.getBean(WalletOpeningMigration.class);
        assertEquals(1, m.run());
        assertEquals(0, m.run());
        wallet.debit(1L, new BigDecimal("2"), WalletType.ADMIN_ADJUSTMENT, "A", "K");
        assertEquals(0, m.run());
        same(new BigDecimal("98"), balance());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                            + " type='OPENING_BALANCE'"));
        reconcile();
    }

    @Test
    void zeroOpeningThenCredit() {
        f.jdbc.update("UPDATE yshop_user SET now_money=0 WHERE id=1");
        assertTrue(wallet.open(1L));
        wallet.credit(1L, new BigDecimal("2"), WalletType.ADMIN_ADJUSTMENT, "A", "K");
        reconcile();
    }

    @Test
    void negativeOpeningStopsBeforeAnyLedger() {
        if (PaymentDatabaseTest.mysqlAcceptance())
            f.jdbc.execute(
                    "ALTER TABLE yshop_user MODIFY now_money DECIMAL(8,2) NOT NULL DEFAULT 0");
        f.jdbc.update("UPDATE yshop_user SET now_money=-0.01 WHERE id=1");
        assertThrows(
                IllegalStateException.class,
                () -> f.ctx.getBean(WalletOpeningMigration.class).run());
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
    }

    @Test
    void genericMemberUpdateCannotOverwriteBalance() {
        var member = new MemberUserDO();
        member.setId(1L);
        member.setNowMoney(new BigDecimal("1"));
        member.setNickname("Synthetic");
        f.ctx.getBean(MemberUserMapper.class).updateById(member);
        same(new BigDecimal("100"), balance());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "0.001", "1000000"})
    void invalidAmounts(String value) {
        assertThrows(
                RuntimeException.class,
                () ->
                        wallet.debit(
                                1L, new BigDecimal(value), WalletType.ADMIN_ADJUSTMENT, "A", "K"));
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
    }

    @Test
    void ledgerConstraintsAndImmutability() {
        wallet.open(1L);
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        f.jdbc.update(
                                "INSERT INTO yshop_member_wallet_transaction SELECT"
                                    + " 'other',uid,direction,type,amount,balance_before,balance_after,business_id,idempotency_key,create_time"
                                    + " FROM yshop_member_wallet_transaction"));
        if (PaymentDatabaseTest.mysqlAcceptance()) {
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            f.jdbc.update(
                                    "UPDATE yshop_member_wallet_transaction SET amount=amount"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> f.jdbc.update("DELETE FROM yshop_member_wallet_transaction"));
        }
        reconcile();
    }

    @Test
    void alternateIdempotencyKeyCannotRepeatBusiness() {
        wallet.debit(1L, new BigDecimal("1"), WalletType.ADMIN_ADJUSTMENT, "A", "K1");
        assertThrows(
                IllegalStateException.class,
                () ->
                        wallet.debit(
                                1L, new BigDecimal("1"), WalletType.ADMIN_ADJUSTMENT, "A", "K2"));
        same(new BigDecimal("99"), balance());
        assertEquals(2, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
        reconcile();
    }

    @Test
    void ordinaryAdminRequestIgnoresMoney() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var vo =
                json.readValue(
                        "{\"id\":1,\"nowMoney\":1,\"nickname\":\"Synthetic\"}",
                        co.yixiang.yshop.module.member.controller.admin.user.vo.UserUpdateReqVO
                                .class);
        assertNull(vo.getNowMoney());
    }

    @Test
    void externalBalanceTamperingRequiresReconciliation() {
        wallet.open(1L);
        f.jdbc.update("UPDATE yshop_user SET now_money=99 WHERE id=1");
        var ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                wallet.debit(
                                        1L,
                                        new BigDecimal("1"),
                                        WalletType.ADMIN_ADJUSTMENT,
                                        "A",
                                        "K"));
        assertEquals("WALLET_RECONCILIATION_REQUIRED", ex.getMessage());
        assertEquals(1, count("SELECT COUNT(*) FROM yshop_member_wallet_transaction"));
    }
}
