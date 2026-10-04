package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.module.order.controller.app.order.param.AppPayParam;
import co.yixiang.yshop.module.order.service.payment.*;
import co.yixiang.yshop.module.order.service.payment.attempt.*;
import co.yixiang.yshop.module.order.service.payment.v3.*;
import co.yixiang.yshop.module.order.service.storeorder.AppStoreOrderServiceImpl;
import co.yixiang.yshop.module.pay.callback.*;
import co.yixiang.yshop.module.pay.v3.*;

import com.wechat.pay.java.core.http.*;
import com.wechat.pay.java.service.payments.model.Transaction;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic official SDK transport and production transactions, never real payment APIs. */
class PaymentLiveReadinessDatabaseTest {
    WechatV3DatabaseTest v;
    WechatV3ReconciliationService recovery;
    AtomicInteger queries = new AtomicInteger(), closes = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        v = new WechatV3DatabaseTest();
        v.setup();
        recovery =
                new WechatV3ReconciliationService(
                        v.attempts, v.factory, v.service, true, true, 0, 60);
        query("NOTPAY");
        when(v.transport.execute(any(), isNull()))
                .thenAnswer(
                        call -> {
                            closes.incrementAndGet();
                            HttpRequest request = call.getArgument(0);
                            assertTrue(
                                    request.getUrl()
                                            .toString()
                                            .endsWith("/" + currentReference() + "/close"));
                            String body =
                                    (String)
                                            request.getBody()
                                                    .getClass()
                                                    .getMethod("getBody")
                                                    .invoke(request.getBody());
                            assertTrue(body.contains("synthetic-merchant"));
                            return mock(HttpResponse.class);
                        });
    }

    @AfterEach
    void close() {
        if (v != null) v.close();
    }

    String currentReference() {
        return v.f.jdbc.queryForObject(
                "SELECT provider_order_reference FROM yshop_order_payment_attempt ORDER BY"
                    + " create_time DESC LIMIT 1",
                String.class);
    }

    Transaction transaction(String state) {
        var data =
                v.transaction(
                        v.f.jdbc.queryForObject(
                                "SELECT provider_order_reference FROM yshop_order_payment_attempt"
                                        + " ORDER BY create_time DESC LIMIT 1",
                                String.class),
                        "synthetic-T1");
        data.put("trade_state", state);
        return com.wechat.pay.java.core.util.GsonUtil.getGson()
                .fromJson(
                        com.wechat.pay.java.core.util.GsonUtil.getGson().toJson(data),
                        Transaction.class);
    }

    void query(String state) {
        doAnswer(
                        call -> {
                            queries.incrementAndGet();
                            HttpRequest request = call.getArgument(0);
                            assertTrue(
                                    request.getUrl()
                                            .toString()
                                            .contains("/out-trade-no/" + currentReference()));
                            assertTrue(
                                    request.getUrl()
                                            .toString()
                                            .contains("mchid=synthetic-merchant"));
                            HttpResponse<Transaction> response = mock(HttpResponse.class);
                            Transaction tx = transaction(state);
                            when(response.getServiceResponse()).thenReturn(tx);
                            return response;
                        })
                .when(v.transport)
                .execute(any(), eq(Transaction.class));
    }

    String claimed() {
        var a = v.attempt();
        assertTrue(v.attempts.claimPrepay(1L, a.getAttemptId()));
        return a.getAttemptId();
    }

    String prepared() {
        v.pay();
        return v.attempt().getAttemptId();
    }

    String state(String id) {
        return v.attempts.read(1L, id).getStatus();
    }

    void active(String id) {
        assertTrue(PaymentAttemptState.valueOf(state(id)).active());
        v.f.effects(0);
        assertEquals(
                1,
                v.f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS"
                                + " NOT NULL"));
    }

    void live(boolean enabled) {
        ReflectionTestUtils.setField(v.attempts, "wechatLiveEnabled", enabled);
        ReflectionTestUtils.setField(
                v.f.ctx.getBean(PaymentProcessor.class), "wechatLiveEnabled", enabled);
    }

    PaymentSuccessEvent ali() {
        return new PaymentSuccessEvent(
                PaymentSuccessEvent.Provider.ALIPAY,
                "merchant-ali",
                "order-A",
                "synthetic-ALI1",
                2,
                "synthetic-app",
                "synthetic-seller",
                "SUCCESS",
                LocalDateTime.now());
    }

    @Test
    void disabledRecoveryNoReadsOrCalls() {
        var a = mock(PaymentAttemptService.class);
        var c = mock(WechatV3ClientFactory.class);
        var p = mock(WechatV3PaymentService.class);
        for (boolean live : List.of(false, true)) {
            var off = new WechatV3ReconciliationService(a, c, p, live, false, 0, 60);
            assertEquals(
                    WechatV3ReconciliationService.Result.DISABLED,
                    off.reconcile("synthetic", true));
            assertTrue(off.candidates(20).isEmpty());
        }
        verifyNoInteractions(a, c, p);
    }

    @Test
    void liveRejectsLegacyAlipayOrderCreation() {
        var target = new AppStoreOrderServiceImpl();
        ReflectionTestUtils.setField(target, "wechatLiveEnabled", true);
        var p = new AppPayParam();
        p.setPaytype("alipay");
        assertEquals(
                "LEGACY_EXTERNAL_PAYMENT_DISABLED",
                assertThrows(IllegalStateException.class, () -> target.pay(1L, p)).getMessage());
    }

    @Test
    void liveRejectsAlipayAttempt() {
        live(true);
        assertThrows(
                IllegalStateException.class,
                () ->
                        v.attempts.createOrGet(
                                1L,
                                "order-A",
                                PaymentSuccessEvent.Provider.ALIPAY,
                                "merchant-ali",
                                "ali"));
        assertEquals(0, v.f.count("SELECT COUNT(*) FROM yshop_order_payment_attempt"));
    }

    @Test
    void activeWechatBlocksOtherAttempt() {
        claimed();
        assertThrows(
                IllegalStateException.class,
                () ->
                        v.attempts.createOrGet(
                                1L,
                                "order-A",
                                PaymentSuccessEvent.Provider.ALIPAY,
                                "merchant-ali",
                                "ali"));
        assertEquals(1, v.f.count("SELECT COUNT(*) FROM yshop_order_payment_attempt"));
    }

    @Test
    void uncertainBlocksLegacyAlipay() {
        String id = claimed();
        assertEquals(PaymentResult.REJECTED, v.f.service.accept(ali()));
        active(id);
    }

    @Test
    void liveRejectsAllLegacyExternalCompletion() {
        live(true);
        assertEquals(PaymentResult.REJECTED, v.f.pay());
        assertEquals(PaymentResult.REJECTED, v.f.service.accept(ali()));
        v.f.effects(0);
    }

    @Test
    void liveRejectsPreexistingBoundAlipay() {
        var a =
                v.attempts.createOrGet(
                        1L, "order-A", PaymentSuccessEvent.Provider.ALIPAY, "merchant-ali", "ali");
        live(true);
        assertEquals(
                PaymentResult.REJECTED,
                v.f.service.acceptAttemptVerified(
                        new PaymentSuccessEvent(
                                PaymentSuccessEvent.Provider.ALIPAY,
                                "merchant-ali",
                                a.getProviderOrderReference(),
                                "synthetic-ALI1",
                                2,
                                "synthetic-app",
                                "synthetic-seller",
                                "SUCCESS",
                                LocalDateTime.now())));
        v.f.effects(0);
    }

    @Test
    void livePreservesBalanceFinalization() {
        live(true);
        var target = v.f.balanceService();
        ReflectionTestUtils.setField(target, "wechatLiveEnabled", true);
        target.yuePay("order-A", 1L);
        v.f.effects(1);
        assertEquals(
                1,
                v.f.count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                                + " type='ORDER_PAYMENT'"));
    }

    @Test
    void successQueryExactlyOnce() {
        String id = claimed();
        query("SUCCESS");
        assertEquals(WechatV3ReconciliationService.Result.SUCCESS, recovery.reconcile(id, true));
        assertEquals(WechatV3ReconciliationService.Result.SKIPPED, recovery.reconcile(id, true));
        assertEquals("PAID", state(id));
        assertEquals(1, queries.get());
        assertEquals(0, closes.get());
        v.f.effects(1);
    }

    @Test
    void notpayNoCloseRetainsSlot() {
        String id = prepared();
        assertEquals(WechatV3ReconciliationService.Result.NOTPAY, recovery.reconcile(id, false));
        active(id);
        assertEquals(0, closes.get());
    }

    @Test
    void confirmedCloseReleasesSlotKeepsMarker() {
        String id = prepared();
        var marker = v.attempts.read(1L, id).getPrepayRequestedAt();
        assertEquals(WechatV3ReconciliationService.Result.TERMINATED, recovery.reconcile(id, true));
        assertEquals("CANCELED", state(id));
        assertEquals(marker, v.attempts.read(1L, id).getPrepayRequestedAt());
        assertEquals("CLOSED", v.attempts.read(1L, id).getRemoteTerminalState());
        assertEquals(
                0,
                v.f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS"
                                + " NOT NULL"));
        assertEquals(1, closes.get());
        v.f.effects(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSED", "REVOKED", "PAYERROR"})
    void remoteTerminalReleasesSlot(String remote) {
        String id = prepared();
        query(remote);
        assertEquals(WechatV3ReconciliationService.Result.TERMINATED, recovery.reconcile(id, true));
        assertEquals(remote.equals("PAYERROR") ? "FAILED" : "CANCELED", state(id));
        assertEquals(0, closes.get());
        v.f.effects(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USERPAYING", "ACCEPT", "REFUND"})
    void otherStatesRemainActive(String remote) {
        String id = prepared();
        query(remote);
        assertEquals(WechatV3ReconciliationService.Result.UNCERTAIN, recovery.reconcile(id, true));
        active(id);
        assertEquals(0, closes.get());
    }

    @Test
    void queryNetworkFailureLeavesFundsAndSlot() {
        String id = claimed();
        doThrow(new IllegalStateException("synthetic-timeout"))
                .when(v.transport)
                .execute(any(), eq(Transaction.class));
        assertEquals(WechatV3ReconciliationService.Result.UNCERTAIN, recovery.reconcile(id, true));
        active(id);
        assertEquals(0, closes.get());
        assertThrows(IllegalStateException.class, v::pay);
        assertEquals(0, v.calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "failure"})
    void closeFailureRetainsActive(String failure) {
        String id = prepared();
        doThrow(new IllegalStateException("synthetic-" + failure))
                .when(v.transport)
                .execute(any(), isNull());
        assertEquals(WechatV3ReconciliationService.Result.UNCERTAIN, recovery.reconcile(id, true));
        active(id);
        assertNull(v.attempts.read(1L, id).getRemoteConfirmedAt());
        assertEquals(5, v.pay().size());
        assertEquals(1, v.calls.get());
    }

    @Test
    void uncertainPrepayNeverResent() {
        doAnswer(
                        c -> {
                            v.calls.incrementAndGet();
                            throw new IllegalStateException("synthetic-timeout");
                        })
                .when(v.transport)
                .execute(
                        any(),
                        eq(com.wechat.pay.java.service.payments.jsapi.model.PrepayResponse.class));
        assertThrows(IllegalStateException.class, v::pay);
        String id = v.attempt().getAttemptId();
        assertEquals(WechatV3ReconciliationService.Result.NOTPAY, recovery.reconcile(id, false));
        assertThrows(IllegalStateException.class, v::pay);
        assertEquals(1, v.calls.get());
        active(id);
    }

    @Test
    void confirmedCloseAllowsNewServerReference() {
        String id = claimed();
        assertEquals(WechatV3ReconciliationService.Result.TERMINATED, recovery.reconcile(id, true));
        v.pay();
        var next = v.attempts.createWechatForPay(1L, "order-A", "merchant-wx");
        assertNotEquals(id, next.getAttemptId());
        assertEquals("PREPAY_CREATED", next.getStatus());
        assertEquals(1, v.calls.get());
        assertEquals(2, v.f.count("SELECT COUNT(*) FROM yshop_order_payment_attempt"));
    }

    @Test
    void recoveryTwentyConcurrentSingleTransition() throws Exception {
        String id = prepared();
        var enter = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            queries.incrementAndGet();
                            enter.countDown();
                            assertTrue(release.await(30, TimeUnit.SECONDS));
                            var response = mock(HttpResponse.class);
                            when(response.getServiceResponse()).thenReturn(transaction("NOTPAY"));
                            return response;
                        })
                .when(v.transport)
                .execute(any(), eq(Transaction.class));
        var pool = Executors.newFixedThreadPool(20);
        try {
            var first = pool.submit(() -> recovery.reconcile(id, true));
            assertTrue(enter.await(30, TimeUnit.SECONDS));
            var rest = new ArrayList<Future<WechatV3ReconciliationService.Result>>();
            for (int i = 0; i < 19; i++) rest.add(pool.submit(() -> recovery.reconcile(id, true)));
            for (var task : rest)
                assertEquals(
                        WechatV3ReconciliationService.Result.SKIPPED,
                        task.get(30, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(
                    WechatV3ReconciliationService.Result.TERMINATED,
                    first.get(30, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, queries.get());
        assertEquals(1, closes.get());
        assertEquals("CANCELED", state(id));
        v.f.effects(0);
    }

    @Test
    void callbackQuerySuccessTwentyConcurrent() throws Exception {
        String id = prepared();
        query("SUCCESS");
        var pool = Executors.newFixedThreadPool(20);
        var gate = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) {
                final boolean callback = i % 2 == 0;
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    if (callback) return v.complete(id, "synthetic-T1");
                                    return recovery.reconcile(id, false);
                                }));
            }
            gate.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals("PAID", state(id));
        v.f.effects(1);
        assertEquals(
                1, v.f.count("SELECT COUNT(*) FROM yshop_order_payment WHERE status='SUCCESS'"));
        assertEquals(0, closes.get());
    }

    @Test
    void callbackBeforeCloseSuppressesClose() throws Exception {
        String id = prepared();
        doAnswer(
                        call -> {
                            assertEquals(
                                    PaymentResult.FIRST_SUCCESS, v.complete(id, "synthetic-T1"));
                            var response = mock(HttpResponse.class);
                            when(response.getServiceResponse()).thenReturn(transaction("NOTPAY"));
                            return response;
                        })
                .when(v.transport)
                .execute(any(), eq(Transaction.class));
        assertEquals(WechatV3ReconciliationService.Result.SKIPPED, recovery.reconcile(id, true));
        assertEquals(0, closes.get());
        assertEquals("PAID", state(id));
        v.f.effects(1);
    }

    @Test
    void callbackDuringCloseNeverDowngradesPaid() throws Exception {
        String id = prepared();
        doAnswer(
                        call -> {
                            assertEquals(
                                    PaymentResult.FIRST_SUCCESS, v.complete(id, "synthetic-T1"));
                            return mock(HttpResponse.class);
                        })
                .when(v.transport)
                .execute(any(), isNull());
        assertEquals(WechatV3ReconciliationService.Result.SKIPPED, recovery.reconcile(id, true));
        assertEquals("PAID", state(id));
        assertNull(v.attempts.read(1L, id).getRemoteTerminalState());
        v.f.effects(1);
    }

    @Test
    void closeThenLateSuccessRequiresReconciliation() throws Exception {
        String id = prepared();
        assertEquals(WechatV3ReconciliationService.Result.TERMINATED, recovery.reconcile(id, true));
        assertEquals(PaymentResult.RECONCILIATION_REQUIRED, v.complete(id, "synthetic-T1"));
        assertEquals("CANCELED", state(id));
        v.f.effects(0);
        assertEquals(
                1,
                v.f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment WHERE"
                                + " status='RECONCILIATION_REQUIRED'"));
    }

    @Test
    void successfulQueryRollbackAndRecovery() {
        String id = prepared();
        query("SUCCESS");
        v.f.jdbc.execute(
                "ALTER TABLE yshop_order_payment ADD CONSTRAINT injected_fault"
                        + " CHECK(status<>'SUCCESS')");
        assertEquals(WechatV3ReconciliationService.Result.UNCERTAIN, recovery.reconcile(id, false));
        active(id);
        v.f.dropFault("yshop_order_payment");
        v.f.service.recover();
        assertEquals("PAID", state(id));
        v.f.effects(1);
    }

    @Test
    void staleLeaseCannotTerminateOrReleaseNewOwner() {
        String id = claimed();
        String old = UUID.randomUUID().toString().replace("-", "");
        assertNotNull(v.attempts.claimReconciliation(id, old, 60, 0));
        v.f.jdbc.update(
                "UPDATE yshop_order_payment_attempt SET"
                    + " reconciliation_lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE"
                    + " attempt_id=?",
                id);
        String current = UUID.randomUUID().toString().replace("-", "");
        assertNotNull(v.attempts.claimReconciliation(id, current, 60, 0));
        assertFalse(v.attempts.confirmRemoteTerminal(id, old, "CLOSED"));
        v.attempts.releaseReconciliation(id, old);
        assertEquals(current, v.attempts.read(1L, id).getReconciliationToken());
        assertTrue(v.attempts.confirmRemoteTerminal(id, current, "CLOSED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"appid", "mchid", "amount", "reference"})
    void mismatchCannotCloseOrFinalize(String field) {
        String id = prepared();
        doAnswer(
                        call -> {
                            var tx = transaction("SUCCESS");
                            switch (field) {
                                case "appid":
                                    tx.setAppid("wrong");
                                    break;
                                case "mchid":
                                    tx.setMchid("wrong");
                                    break;
                                case "amount":
                                    tx.getAmount().setTotal(3);
                                    break;
                                default:
                                    tx.setOutTradeNo("wrong");
                            }
                            var response = mock(HttpResponse.class);
                            when(response.getServiceResponse()).thenReturn(tx);
                            return response;
                        })
                .when(v.transport)
                .execute(any(), eq(Transaction.class));
        assertEquals(WechatV3ReconciliationService.Result.UNCERTAIN, recovery.reconcile(id, true));
        active(id);
        assertEquals(0, closes.get());
    }

    @Test
    void unrequestedAttemptNotClaimed() {
        String id = v.attempt().getAttemptId();
        assertEquals(WechatV3ReconciliationService.Result.SKIPPED, recovery.reconcile(id, true));
        assertEquals(0, queries.get());
        assertEquals(0, closes.get());
    }

    @Test
    void remoteProofAndTerminalImmutableOnMysql() {
        String id = prepared();
        recovery.reconcile(id, true);
        if (PaymentDatabaseTest.mysqlAcceptance()) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            v.f.jdbc.update(
                                    "UPDATE yshop_order_payment_attempt SET"
                                            + " remote_terminal_state=NULL,remote_confirmed_at=NULL"
                                            + " WHERE attempt_id=?",
                                    id));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            v.f.jdbc.update(
                                    "UPDATE yshop_order_payment_attempt SET status='CREATED' WHERE"
                                            + " attempt_id=?",
                                    id));
        }
        assertEquals("CANCELED", state(id));
    }

    @Test
    void migrationRerunPreservesProof() throws Exception {
        String id = prepared();
        recovery.reconcile(id, true);
        var before = v.attempts.read(1L, id);
        if (PaymentDatabaseTest.mysqlAcceptance()) {
            var process =
                    new ProcessBuilder(
                                    "python3",
                                    "../../../tests/payment/mysql-acceptance.py",
                                    "--install-recovery-migration")
                            .redirectErrorStream(true)
                            .start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
        }
        var after = v.attempts.read(1L, id);
        assertEquals(before.getRemoteConfirmedAt(), after.getRemoteConfirmedAt());
        assertEquals(before.getPrepayReference(), after.getPrepayReference());
        assertEquals("CANCELED", after.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"callback-first", "close-first"})
    void callbackCloseOnSeparateThreads(String winner) throws Exception {
        String id = prepared();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            closes.incrementAndGet();
                            entered.countDown();
                            assertTrue(release.await(30, TimeUnit.SECONDS));
                            return mock(HttpResponse.class);
                        })
                .when(v.transport)
                .execute(any(), isNull());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var closing = pool.submit(() -> recovery.reconcile(id, true));
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            if (winner.equals("callback-first")) {
                assertEquals(
                        PaymentResult.FIRST_SUCCESS,
                        pool.submit(() -> v.complete(id, "synthetic-T1"))
                                .get(30, TimeUnit.SECONDS));
                release.countDown();
                assertEquals(
                        WechatV3ReconciliationService.Result.SKIPPED,
                        closing.get(30, TimeUnit.SECONDS));
                assertEquals("PAID", state(id));
                v.f.effects(1);
            } else {
                release.countDown();
                assertEquals(
                        WechatV3ReconciliationService.Result.TERMINATED,
                        closing.get(30, TimeUnit.SECONDS));
                assertEquals(
                        PaymentResult.RECONCILIATION_REQUIRED,
                        pool.submit(() -> v.complete(id, "synthetic-T1"))
                                .get(30, TimeUnit.SECONDS));
                assertEquals("CANCELED", state(id));
                v.f.effects(0);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, closes.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"query-valid", "query-invalid", "close-valid", "close-error"})
    void officialSdkQueryCloseSignedFakeHttp(String scenario) throws Exception {
        String id = claimed();
        var sdkHttp =
                new okhttp3.OkHttpClient.Builder()
                        .retryOnConnectionFailure(false)
                        .addInterceptor(
                                chain -> {
                                    assertNotNull(chain.request().header("Authorization"));
                                    assertTrue(
                                            chain.request()
                                                    .header("Authorization")
                                                    .contains("synthetic-serial"));
                                    String body =
                                            scenario.startsWith("query")
                                                    ? com.wechat.pay.java.core.util.GsonUtil
                                                            .getGson()
                                                            .toJson(transaction("NOTPAY"))
                                                    : scenario.equals("close-error")
                                                            ? "{\"code\":\"SYSTEM_ERROR\",\"message\":\"synthetic\"}"
                                                            : "";
                                    String
                                            timestamp =
                                                    Long.toString(
                                                            java.time.Instant.now()
                                                                    .getEpochSecond()),
                                            nonce = "synthetic-response";
                                    String signature =
                                            new com.wechat.pay.java.core.cipher.RSASigner(
                                                            "PUB_KEY_ID_SYNTHETIC",
                                                            WechatV3DatabaseTest.PLATFORM
                                                                    .getPrivate())
                                                    .sign(
                                                            timestamp + "\n" + nonce + "\n" + body
                                                                    + "\n")
                                                    .getSign();
                                    return new okhttp3.Response.Builder()
                                            .request(chain.request())
                                            .protocol(okhttp3.Protocol.HTTP_1_1)
                                            .code(
                                                    scenario.equals("close-valid")
                                                            ? 204
                                                            : scenario.equals("close-error")
                                                                    ? 500
                                                                    : 200)
                                            .message("synthetic")
                                            .header("Wechatpay-Serial", "PUB_KEY_ID_SYNTHETIC")
                                            .header("Wechatpay-Timestamp", timestamp)
                                            .header("Wechatpay-Nonce", nonce)
                                            .header(
                                                    "Wechatpay-Signature",
                                                    scenario.equals("query-invalid")
                                                            ? "AAAA"
                                                            : signature)
                                            .body(
                                                    okhttp3.ResponseBody.create(
                                                            okhttp3.MediaType.parse(
                                                                    "application/json"),
                                                            body))
                                            .build();
                                })
                        .build();
        var transport =
                new DefaultHttpClientBuilder()
                        .config(v.config)
                        .okHttpClient(sdkHttp)
                        .disableRetryOnConnectionFailure()
                        .build();
        var client =
                new OfficialWechatV3Client(
                        "synthetic-app",
                        "synthetic-merchant",
                        "https://synthetic.invalid/app-api/order/notify/wechat-v3/merchant-wx",
                        v.config,
                        transport);
        if (scenario.equals("query-valid"))
            assertEquals(Transaction.TradeStateEnum.NOTPAY, client.query(id).getTradeState());
        else if (scenario.equals("query-invalid"))
            assertThrows(IllegalStateException.class, () -> client.query(id));
        else if (scenario.equals("close-valid")) assertDoesNotThrow(() -> client.close(id));
        else assertThrows(IllegalStateException.class, () -> client.close(id));
        active(id);
    }

    @Test
    void walletPaidOrderCanCloseOutstandingUnpaidWechatAttempt() {
        String id = prepared();
        v.f.balanceService().yuePay("order-A", 1L);
        v.f.effects(1);
        assertEquals(WechatV3ReconciliationService.Result.TERMINATED, recovery.reconcile(id, true));
        assertEquals("CANCELED", state(id));
        assertEquals(1, closes.get());
        v.f.effects(1);
        assertEquals(
                1,
                v.f.count(
                        "SELECT COUNT(*) FROM yshop_member_wallet_transaction WHERE"
                                + " type='ORDER_PAYMENT'"));
    }

    @Test
    void walletPaidOrderWithWechatSuccessRequiresReviewNotSecondFulfillment() {
        String id = prepared();
        v.f.balanceService().yuePay("order-A", 1L);
        query("SUCCESS");
        assertEquals(
                WechatV3ReconciliationService.Result.RECONCILIATION, recovery.reconcile(id, true));
        v.f.effects(1);
        assertEquals(0, closes.get());
        assertEquals(
                1,
                v.f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment WHERE"
                                + " status='PAYMENT_CONFLICT'"));
    }
}
