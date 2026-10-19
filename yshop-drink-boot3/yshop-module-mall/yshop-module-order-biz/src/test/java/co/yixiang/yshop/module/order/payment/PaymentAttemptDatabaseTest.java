package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;

import co.yixiang.yshop.module.order.service.payment.attempt.*;
import co.yixiang.yshop.module.pay.callback.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** Same production services and mappers on H2 and isolated MySQL 8/InnoDB; no provider calls. */
class PaymentAttemptDatabaseTest {
    PaymentDatabaseTest f;
    PaymentAttemptService attempts;

    @BeforeEach
    void setup() throws Exception {
        f = new PaymentDatabaseTest();
        f.setup();
        attempts = f.ctx.getBean(PaymentAttemptService.class);
    }

    @AfterEach
    void close() {
        if (f != null) f.close();
    }

    PaymentAttempt create() {
        return create("order-A", "key-A");
    }

    PaymentAttempt create(String order, String key) {
        return attempts.createOrGet(
                1L, order, PaymentSuccessEvent.Provider.WECHAT, "merchant-wx", key);
    }

    PaymentSuccessEvent event(PaymentAttempt a, String transaction) {
        return new PaymentSuccessEvent(
                PaymentSuccessEvent.Provider.valueOf(a.getProvider()),
                a.getMerchantDetailsId(),
                a.getProviderOrderReference(),
                transaction,
                a.getAmountCents(),
                a.getAppid(),
                a.getMerchantIdentity(),
                "SUCCESS",
                LocalDateTime.now());
    }

    PaymentResult complete(PaymentAttempt a) {
        return f.service.acceptAttemptVerified(event(a, "synthetic-T1"));
    }

    int count() {
        return f.count("SELECT COUNT(*) FROM yshop_order_payment_attempt");
    }

    void unpaid(PaymentAttempt a) {
        assertEquals(
                0,
                f.count(
                        "SELECT paid FROM yshop_store_order WHERE order_id='"
                                + a.getOrderId()
                                + "'"));
        assertEquals("CREATED", attempts.read(1L, a.getAttemptId()).getStatus());
        f.effects(0);
    }

    <T> List<T> concurrent(int n, Callable<T> action) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        var gate = new CountDownLatch(1);
        var ready = new CountDownLatch(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(gate.await(30, TimeUnit.SECONDS));
                                    return action.call();
                                }));
            assertTrue(ready.await(30, TimeUnit.SECONDS), "All workers must be ready before release");
            gate.countDown();
            List<T> result = new ArrayList<>();
            for (var future : futures) result.add(future.get(60, TimeUnit.SECONDS));
            return result;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void createdSnapshotAndReference() {
        var a = create();
        assertEquals(32, a.getAttemptId().length());
        assertTrue(a.getAttemptId().matches("[a-f0-9]{32}"));
        assertEquals(a.getAttemptId(), a.getProviderOrderReference());
        assertNotEquals(a.getOrderId(), a.getProviderOrderReference());
        assertEquals(2, a.getAmountCents());
        assertEquals("CNY", a.getCurrency());
        assertEquals("CREATED", a.getStatus());
        assertEquals("synthetic-app", a.getAppid());
        assertEquals("synthetic-merchant", a.getMerchantIdentity());
        assertNotNull(a.getCreateTime());
        assertNull(a.getPrepayReference());
        assertNull(a.getProviderTransactionId());
        assertEquals(1, count());
    }

    @Test
    void sameKeyReturnsOriginal() {
        assertEquals(create().getAttemptId(), create().getAttemptId());
        assertEquals(1, count());
    }

    @Test
    void twentyConcurrentCreatesOneActiveRecord() throws Exception {
        var result = concurrent(20, this::create);
        assertEquals(1, result.stream().map(PaymentAttempt::getAttemptId).distinct().count());
        assertEquals(1, count());
        assertEquals(
                1,
                f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS"
                                + " NOT NULL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "merchant", "key"})
    void activeBindingConflict(String change) {
        create();
        assertThrows(
                IllegalStateException.class,
                () ->
                        attempts.createOrGet(
                                1L,
                                "order-A",
                                change.equals("provider")
                                        ? PaymentSuccessEvent.Provider.ALIPAY
                                        : PaymentSuccessEvent.Provider.WECHAT,
                                change.equals("provider")
                                        ? "merchant-ali"
                                        : change.equals("merchant")
                                                ? "merchant-wx-2"
                                                : "merchant-wx",
                                change.equals("key") ? "key-B" : "key-A"));
        assertEquals(1, count());
    }

    @Test
    void differentKeyCannotSilentlySwitchMerchant() {
        create();
        assertThrows(
                IllegalStateException.class,
                () ->
                        attempts.createOrGet(
                                1L,
                                "order-A",
                                PaymentSuccessEvent.Provider.WECHAT,
                                "merchant-wx-2",
                                "key-B"));
        assertEquals(1, count());
    }

    @Test
    void amountChangedCannotReuseOrRecordPrepay() {
        var a = create();
        f.jdbc.update("UPDATE yshop_store_order SET pay_price=0.03 WHERE id=1");
        assertThrows(IllegalStateException.class, this::create);
        assertThrows(
                IllegalStateException.class,
                () -> attempts.recordPrepay(1L, a.getAttemptId(), "synthetic-prepay"));
        assertEquals(2, attempts.read(1L, a.getAttemptId()).getAmountCents());
    }

    @ParameterizedTest
    @ValueSource(strings = {"appid", "identity"})
    void merchantSnapshotChangeConflicts(String field) {
        create();
        f.jdbc.update(
                "UPDATE merchant_details SET "
                        + (field.equals("appid")
                                ? "appid='synthetic-other-app'"
                                : "mch_id='synthetic-other-merchant'")
                        + " WHERE details_id='merchant-wx'");
        assertThrows(IllegalStateException.class, this::create);
        assertEquals(1, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "EXPIRED", "CANCELED"})
    void terminateThenNewKeyRetriesWithoutReactivation(String state) {
        var a = create();
        var terminal = PaymentAttemptState.valueOf(state);
        assertEquals(state, attempts.terminateCreated(1L, a.getAttemptId(), terminal).getStatus());
        assertEquals(state, attempts.terminateCreated(1L, a.getAttemptId(), terminal).getStatus());
        assertEquals(state, create().getStatus());
        var retry = create("order-A", "key-B");
        assertNotEquals(a.getAttemptId(), retry.getAttemptId());
        assertEquals(2, count());
        assertEquals(
                1,
                f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS"
                                + " NOT NULL"));
    }

    @Test
    void prepayCanReplayButNotOverwriteOrTerminate() {
        var a = create();
        assertEquals(
                "PREPAY_CREATED",
                attempts.recordPrepay(1L, a.getAttemptId(), "synthetic-prepay").getStatus());
        assertEquals(
                a.getAttemptId(),
                attempts.recordPrepay(1L, a.getAttemptId(), "synthetic-prepay").getAttemptId());
        assertThrows(
                IllegalStateException.class,
                () -> attempts.recordPrepay(1L, a.getAttemptId(), "synthetic-other-prepay"));
        assertThrows(
                IllegalStateException.class,
                () -> attempts.terminateCreated(1L, a.getAttemptId(), PaymentAttemptState.EXPIRED));
        assertThrows(IllegalStateException.class, () -> create("order-A", "key-B"));
    }

    @Test
    void invalidTerminationDoesNotReleaseSlot() {
        var a = create();
        assertThrows(
                IllegalStateException.class,
                () -> attempts.terminateCreated(1L, a.getAttemptId(), PaymentAttemptState.PAID));
        assertEquals("CREATED", create().getStatus());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "paid=1",
                "status=1",
                "status=-1",
                "refund_status=1",
                "deleted=1",
                "is_system_del=1",
                "uid=2",
                "pay_price=0",
                "pay_price=-1"
            })
    void unpayableOrderRejected(String change) {
        if (PaymentDatabaseTest.mysqlAcceptance() && change.equals("pay_price=-1")) {
            // Upstream MySQL pay_price is unsigned: invalid money is rejected before service entry.
            assertThrows(
                    RuntimeException.class,
                    () -> f.jdbc.update("UPDATE yshop_store_order SET " + change + " WHERE id=1"));
            assertEquals(0, count());
            return;
        }
        f.jdbc.update("UPDATE yshop_store_order SET " + change + " WHERE id=1");
        assertThrows(RuntimeException.class, this::create);
        assertEquals(0, count());
    }

    @Test
    void unknownOrderRejected() {
        assertThrows(IllegalStateException.class, () -> create("missing", "key"));
        assertEquals(0, count());
    }

    @Test
    void wrongOwnerCannotReadOrChange() {
        var a = create();
        assertThrows(IllegalStateException.class, () -> attempts.read(2L, a.getAttemptId()));
        assertThrows(
                IllegalStateException.class,
                () -> attempts.recordPrepay(2L, a.getAttemptId(), "synthetic-prepay"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        attempts.terminateCreated(
                                2L, a.getAttemptId(), PaymentAttemptState.CANCELED));
        assertEquals("CREATED", create().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "merchant-ali"})
    void absentOrWrongProviderMerchantRejected(String id) {
        assertThrows(
                IllegalStateException.class,
                () ->
                        attempts.createOrGet(
                                1L, "order-A", PaymentSuccessEvent.Provider.WECHAT, id, "key"));
        assertEquals(0, count());
    }

    @Test
    void internalProviderRejected() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        attempts.createOrGet(
                                1L,
                                "order-A",
                                PaymentSuccessEvent.Provider.BALANCE,
                                "merchant-wx",
                                "key"));
        assertEquals(0, count());
    }

    @Test
    void prepayReferenceCannotBindTwoAttempts() {
        var a = create();
        f.order("order-B", 2);
        var b = create("order-B", "key-B");
        attempts.recordPrepay(1L, a.getAttemptId(), "synthetic-prepay");
        assertThrows(
                RuntimeException.class,
                () -> attempts.recordPrepay(1L, b.getAttemptId(), "synthetic-prepay"));
        assertEquals("CREATED", attempts.read(1L, b.getAttemptId()).getStatus());
        assertNull(attempts.read(1L, b.getAttemptId()).getPrepayReference());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PREPAY_CREATED"})
    void boundSuccessAndReplay(String initial) {
        var a = create();
        if (initial.equals("PREPAY_CREATED"))
            attempts.recordPrepay(1L, a.getAttemptId(), "synthetic-prepay");
        assertEquals(PaymentResult.FIRST_SUCCESS, complete(a));
        assertEquals(PaymentResult.IDEMPOTENT_DUPLICATE, complete(a));
        f.effects(1);
        var paid = attempts.read(1L, a.getAttemptId());
        assertEquals("PAID", paid.getStatus());
        assertEquals("synthetic-T1", paid.getProviderTransactionId());
        assertNotNull(paid.getPaidAt());
        assertNotNull(paid.getPaymentEventId());
        assertEquals(
                a.getAttemptId(),
                f.jdbc.queryForObject("SELECT attempt_id FROM yshop_order_payment", String.class));
        assertEquals(
                "order-A",
                f.jdbc.queryForObject("SELECT order_id FROM yshop_order_payment", String.class));
        assertEquals(
                a.getProviderOrderReference(),
                f.jdbc.queryForObject(
                        "SELECT out_trade_no FROM yshop_order_payment", String.class));
        assertThrows(IllegalStateException.class, this::create);
        assertThrows(
                IllegalStateException.class,
                () -> attempts.recordPrepay(1L, a.getAttemptId(), "synthetic-prepay"));
    }

    @Test
    void alipayBoundSuccessUsesSellerSnapshot() {
        var a =
                attempts.createOrGet(
                        1L,
                        "order-A",
                        PaymentSuccessEvent.Provider.ALIPAY,
                        "merchant-ali",
                        "key-A");
        assertEquals("synthetic-seller", a.getMerchantIdentity());
        assertEquals(PaymentResult.FIRST_SUCCESS, complete(a));
        f.effects(1);
    }

    @Test
    void twentyConcurrentCompletionsOneFulfillment() throws Exception {
        var a = create();
        var result = concurrent(20, () -> complete(a));
        assertEquals(1, Collections.frequency(result, PaymentResult.FIRST_SUCCESS));
        assertEquals(19, Collections.frequency(result, PaymentResult.IDEMPOTENT_DUPLICATE));
        f.effects(1);
        assertEquals(1, count());
        assertEquals("PAID", attempts.read(1L, a.getAttemptId()).getStatus());
    }

    @Test
    void transactionCannotBindTwoAttempts() {
        var a = create();
        f.order("order-B", 2);
        var b = create("order-B", "key-B");
        assertEquals(PaymentResult.FIRST_SUCCESS, complete(a));
        assertEquals(PaymentResult.REJECTED, complete(b));
        assertEquals("CREATED", attempts.read(1L, b.getAttemptId()).getStatus());
        assertEquals(0, f.count("SELECT paid FROM yshop_store_order WHERE id=2"));
        f.effects(1);
        assertEquals(1, f.count("SELECT COUNT(*) FROM yshop_order_payment_conflict"));
    }

    @Test
    void twoTransactionsRaceOneAttempt() throws Exception {
        var a = create();
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            List<Future<PaymentResult>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String tx = "synthetic-T" + i;
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return f.service.acceptAttemptVerified(event(a, tx));
                                }));
            }
            gate.countDown();
            var results = new ArrayList<PaymentResult>();
            for (var job : jobs) results.add(job.get(60, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(results, PaymentResult.FIRST_SUCCESS));
            assertEquals(1, Collections.frequency(results, PaymentResult.RECONCILIATION_REQUIRED));
            f.effects(1);
            assertEquals(
                    1,
                    f.count(
                            "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE"
                                    + " provider_transaction_id IS NOT NULL"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "merchant", "amount", "appid", "identity"})
    void wrongBindingDurablyRejected(String change) {
        var a = create();
        var e = event(a, "synthetic-T1");
        e =
                new PaymentSuccessEvent(
                        change.equals("provider")
                                ? PaymentSuccessEvent.Provider.ALIPAY
                                : e.provider(),
                        change.equals("merchant") ? "merchant-wx-2" : e.merchantDetailsId(),
                        e.outTradeNo(),
                        e.providerTransactionId(),
                        change.equals("amount") ? 3 : e.totalFeeCents(),
                        change.equals("appid") ? "synthetic-other-app" : e.appid(),
                        change.equals("identity") ? "synthetic-other-merchant" : e.mchId(),
                        e.resultCode(),
                        e.receivedAt());
        assertEquals(PaymentResult.REJECTED, f.service.acceptAttemptVerified(e));
        assertEquals(1, f.count("SELECT COUNT(*) FROM yshop_order_payment"));
        unpaid(a);
    }

    @Test
    void unknownReferenceDoesNotGuessOrderBinding() {
        assertEquals(
                PaymentResult.UNKNOWN_ORDER,
                f.service.acceptAttemptVerified(f.event("order-A", "synthetic-T1", 2)));
        assertEquals(0, f.count("SELECT COUNT(*) FROM yshop_order_payment"));
        f.effects(0);
    }

    @Test
    void orderAmountChangeCannotComplete() {
        var a = create();
        f.jdbc.update("UPDATE yshop_store_order SET pay_price=0.03 WHERE id=1");
        assertEquals(PaymentResult.REJECTED, complete(a));
        unpaid(a);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "EXPIRED", "CANCELED"})
    void lateSuccessRequiresReconciliation(String state) {
        var a = create();
        attempts.terminateCreated(1L, a.getAttemptId(), PaymentAttemptState.valueOf(state));
        assertEquals(PaymentResult.RECONCILIATION_REQUIRED, complete(a));
        assertEquals(state, attempts.read(1L, a.getAttemptId()).getStatus());
        assertNull(attempts.read(1L, a.getAttemptId()).getProviderTransactionId());
        assertEquals(0, f.count("SELECT paid FROM yshop_store_order WHERE id=1"));
        f.effects(0);
    }

    @Test
    void legacyCannotCompleteWechatAttemptOrder() {
        var a=create();assertEquals(PaymentResult.REJECTED,f.pay());unpaid(a);
        assertEquals(PaymentResult.FIRST_SUCCESS,f.service.acceptAttemptVerified(event(a,"synthetic-bound-other")));f.effects(1);
        assertEquals("PAID",attempts.read(1L,a.getAttemptId()).getStatus());
        assertEquals(1,f.count("SELECT COUNT(*) FROM yshop_order_payment WHERE attempt_id IS NULL AND status='PAYMENT_CONFLICT'"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "yshop_user_bill",
                "yshop_store_order_status",
                "yshop_order_payment_attempt",
                "yshop_order_payment"
            })
    void completionFaultRollsBackAttemptAndEffectsThenRecovers(String table) {
        var a = create();
        String predicate =
                table.equals("yshop_user_bill")
                        ? "number<0"
                        : table.equals("yshop_store_order_status")
                                ? "change_type<>'pay_success'"
                                : table.equals("yshop_order_payment_attempt")
                                        ? "status<>'PAID'"
                                        : "status<>'SUCCESS'";
        f.jdbc.execute(
                "ALTER TABLE " + table + " ADD CONSTRAINT injected_fault CHECK(" + predicate + ")");
        assertEquals(PaymentResult.RETRY, complete(a));
        unpaid(a);
        assertEquals(
                1,
                f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment WHERE"
                                + " status='FAILED_RETRYABLE'"));
        assertNull(attempts.read(1L, a.getAttemptId()).getPaymentEventId());
        assertNull(attempts.read(1L, a.getAttemptId()).getProviderTransactionId());
        f.dropFault(table);
        f.service.recover();
        assertEquals("PAID", attempts.read(1L, a.getAttemptId()).getStatus());
        f.effects(1);
        assertEquals(PaymentResult.IDEMPOTENT_DUPLICATE, complete(a));
    }

    @Test
    void receiptCannotInvertCallerLockOrder() {
        var a = create();
        var tx = new TransactionTemplate(f.ctx.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(
                status -> {
                    f.orders.lockPaymentOrder("order-A");
                    assertThrows(IllegalStateException.class, () -> complete(a));
                });
        assertEquals(0, f.count("SELECT COUNT(*) FROM yshop_order_payment"));
        unpaid(a);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "amount_cents=3",
                "uid=2",
                "order_id='other'",
                "idempotency_key='other'",
                "provider='ALIPAY'",
                "merchant_details_id='other'",
                "appid='other'",
                "merchant_identity='other'",
                "provider_order_reference='other'",
                "currency='USD'"
            })
    void directSqlSnapshotTamperingRejectedOnMysql(String change) {
        var a = create();
        if (PaymentDatabaseTest.mysqlAcceptance())
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.jdbc.update(
                                    "UPDATE yshop_order_payment_attempt SET "
                                            + change
                                            + " WHERE attempt_id=?",
                                    a.getAttemptId()));
        // H2 exercises the limited-update service API; trigger protection is verified only on
        // MySQL.
        assertEquals(2, attempts.read(1L, a.getAttemptId()).getAmountCents());
        assertEquals("merchant-wx", attempts.read(1L, a.getAttemptId()).getMerchantDetailsId());
        assertEquals(1, count());
    }

    @Test
    void directSqlReferenceOverwriteDeleteAndReactivationRejectedOnMysql() {
        var a = create();
        attempts.recordPrepay(1L, a.getAttemptId(), "synthetic-prepay");
        assertEquals(PaymentResult.FIRST_SUCCESS, complete(a));
        if (PaymentDatabaseTest.mysqlAcceptance()) {
            for (String change :
                    List.of(
                            "prepay_reference='other'",
                            "provider_transaction_id='other'",
                            "payment_event_id='other'",
                            "status='CREATED'",
                            "paid_at=NULL"))
                assertThrows(
                        RuntimeException.class,
                        () ->
                                f.jdbc.update(
                                        "UPDATE yshop_order_payment_attempt SET "
                                                + change
                                                + " WHERE attempt_id=?",
                                        a.getAttemptId()));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.jdbc.update(
                                    "DELETE FROM yshop_order_payment_attempt WHERE attempt_id=?",
                                    a.getAttemptId()));
        }
        assertEquals("PAID", attempts.read(1L, a.getAttemptId()).getStatus());
        assertEquals("synthetic-prepay", attempts.read(1L, a.getAttemptId()).getPrepayReference());
        f.effects(1);
    }

    @Test
    void migrationRerunPreservesAttemptAndHistoricalNull() throws Exception {
        var a = create();
        f.order("order-B", 2);
        assertEquals(
                PaymentResult.FIRST_SUCCESS,
                f.service.accept(f.event("order-B", "synthetic-legacy", 2)));
        if (PaymentDatabaseTest.mysqlAcceptance()) {
            var process =
                    new ProcessBuilder(
                                    "python3",
                                    "../../../tests/payment/mysql-acceptance.py",
                                    "--install-attempt-migration")
                            .redirectErrorStream(true)
                            .start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
        }
        assertEquals(1, count());
        assertEquals(a.getAttemptId(), create().getAttemptId());
        assertEquals(
                1, f.count("SELECT COUNT(*) FROM yshop_order_payment WHERE attempt_id IS NULL"));
        assertEquals(
                0,
                f.count("SELECT COUNT(*) FROM yshop_order_payment WHERE attempt_id IS NOT NULL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted=1", "appid=NULL", "mch_id=NULL", "pay_type='cash'"})
    void invalidMerchantMetadataRejected(String change) {
        f.jdbc.update("UPDATE merchant_details SET " + change + " WHERE details_id='merchant-wx'");
        assertThrows(IllegalStateException.class, this::create);
        assertEquals(0, count());
    }

    @Test
    void differentKeysConcurrentStillOneActive() throws Exception {
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var result =
                concurrent(
                        20,
                        () -> {
                            try {
                                return create("order-A", "key-" + sequence.incrementAndGet())
                                        .getAttemptId();
                            } catch (IllegalStateException conflict) {
                                assertEquals(
                                        "ACTIVE_PAYMENT_ATTEMPT_EXISTS", conflict.getMessage());
                                return "conflict";
                            }
                        });
        assertEquals(19, Collections.frequency(result, "conflict"));
        assertEquals(1, count());
    }

    @Test
    void terminatedAttemptSuccessCannotFulfillReplacement() {
        var old = create();
        attempts.terminateCreated(1L, old.getAttemptId(), PaymentAttemptState.CANCELED);
        var next = create("order-A", "key-B");
        assertEquals(PaymentResult.RECONCILIATION_REQUIRED, complete(old));
        assertEquals("CREATED", attempts.read(1L, next.getAttemptId()).getStatus());
        assertEquals(0, f.count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(
                PaymentResult.FIRST_SUCCESS,
                f.service.acceptAttemptVerified(event(next, "synthetic-retry-success")));
        f.effects(1);
    }
}
