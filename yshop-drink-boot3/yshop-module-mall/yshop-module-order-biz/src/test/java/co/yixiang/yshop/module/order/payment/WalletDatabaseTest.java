package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;

import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.dal.mysql.wallet.*;
import co.yixiang.yshop.module.member.service.wallet.*;
import co.yixiang.yshop.module.pay.callback.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

class WalletDatabaseTest {
    PaymentDatabaseTest f;
    WalletService wallet;
    RechargeService recharge;

    @BeforeEach
    void setup() throws Exception {
        f = new PaymentDatabaseTest();
        f.setup();
        wallet = f.ctx.getBean(WalletService.class);
        recharge = f.ctx.getBean(RechargeService.class);
    }

    @AfterEach
    void close() {
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
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int n = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return task.apply(n);
                                }));
            }
            gate.countDown();
            List<Boolean> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
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
