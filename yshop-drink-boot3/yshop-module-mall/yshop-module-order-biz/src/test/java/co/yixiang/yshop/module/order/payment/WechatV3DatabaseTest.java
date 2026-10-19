package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.module.order.service.payment.attempt.*;
import co.yixiang.yshop.module.order.service.payment.v3.WechatV3PaymentService;
import co.yixiang.yshop.module.pay.callback.*;
import co.yixiang.yshop.module.pay.v3.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.cipher.RSASigner;
import com.wechat.pay.java.core.http.*;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.model.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Official SDK with generated local keys and fake transport. No provider network, no real money.
 */
class WechatV3DatabaseTest {
    static final KeyPair MERCHANT = keyPair(), PLATFORM = keyPair();

    static KeyPair keyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("SYNTHETIC_KEY_SETUP_FAILED");
        }
    }

    PaymentDatabaseTest f;
    PaymentAttemptService attempts;
    WechatV3PaymentService service;
    WechatV3ClientFactory factory;
    OfficialWechatV3Client client;
    HttpClient transport;
    RSAPublicKeyConfig config;
    final AtomicInteger calls = new AtomicInteger();
    final List<PrepayRequest> sent = new CopyOnWriteArrayList<>();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        f = new PaymentDatabaseTest();
        f.setup();
        attempts = f.ctx.getBean(PaymentAttemptService.class);
        config =
                new RSAPublicKeyConfig.Builder()
                        .merchantId("synthetic-merchant")
                        .merchantSerialNumber("synthetic-serial")
                        .privateKey(MERCHANT.getPrivate())
                        .publicKey(PLATFORM.getPublic())
                        .publicKeyId("PUB_KEY_ID_SYNTHETIC")
                        .apiV3Key(UUID.randomUUID().toString().replace("-", ""))
                        .build();
        transport = mock(HttpClient.class);
        when(transport.execute(any(), eq(PrepayResponse.class)))
                .thenAnswer(
                        call -> {
                            calls.incrementAndGet();
                            HttpRequest req = call.getArgument(0);
                            assertTrue(
                                    req.getUrl().toString().endsWith("/v3/pay/transactions/jsapi"));
                            String text =
                                    (String)
                                            req.getBody()
                                                    .getClass()
                                                    .getMethod("getBody")
                                                    .invoke(req.getBody());
                            sent.add(
                                    com.wechat.pay.java.core.util.GsonUtil.getGson()
                                            .fromJson(text, PrepayRequest.class));
                            PrepayResponse response = new PrepayResponse();
                            response.setPrepayId("synthetic-prepay");
                            HttpResponse<PrepayResponse> http = mock(HttpResponse.class);
                            when(http.getServiceResponse()).thenReturn(response);
                            return http;
                        });
        client =
                new OfficialWechatV3Client(
                        "synthetic-app",
                        "synthetic-merchant",
                        "https://synthetic.invalid/app-api/order/notify/wechat-v3/merchant-wx",
                        config,
                        transport);
        factory = mock(WechatV3ClientFactory.class);
        when(factory.forMerchant(anyString())).thenReturn(client);
        service = new WechatV3PaymentService(attempts, factory, f.service);
    }

    @AfterEach
    void close() {
        if (f != null) f.close();
    }

    Map<String, String> pay() {
        return service.pay(1L, "order-A", "merchant-wx", "synthetic-openid");
    }

    PaymentAttempt attempt() {
        return attempts.createOrGet(
                1L,
                "order-A",
                PaymentSuccessEvent.Provider.WECHAT,
                "merchant-wx",
                "wechat-v3-jsapi");
    }

    Map<String, Object> transaction(String reference, String transaction) {
        return new LinkedHashMap<>(
                Map.of(
                        "appid",
                        "synthetic-app",
                        "mchid",
                        "synthetic-merchant",
                        "out_trade_no",
                        reference,
                        "transaction_id",
                        transaction,
                        "trade_state",
                        "SUCCESS",
                        "trade_type",
                        "JSAPI",
                        "amount",
                        Map.of("total", 2, "currency", "CNY")));
    }

    RequestParam notification(
            Map<String, Object> transaction, boolean badSignature, boolean badCipher)
            throws Exception {
        byte[] aad = "transaction".getBytes(StandardCharsets.UTF_8),
                nonce = "synthetic123".getBytes(StandardCharsets.UTF_8);
        String ciphertext =
                config.createAeadCipher().encrypt(aad, nonce, json.writeValueAsBytes(transaction));
        if (badCipher)
            ciphertext = (ciphertext.startsWith("A") ? "B" : "A") + ciphertext.substring(1);
        String body =
                json.writeValueAsString(
                        Map.of(
                                "id",
                                "synthetic-event",
                                "event_type",
                                "TRANSACTION.SUCCESS",
                                "resource_type",
                                "encrypt-resource",
                                "resource",
                                Map.of(
                                        "algorithm",
                                        "AEAD_AES_256_GCM",
                                        "original_type",
                                        "transaction",
                                        "associated_data",
                                        "transaction",
                                        "nonce",
                                        "synthetic123",
                                        "ciphertext",
                                        ciphertext)));
        String timestamp = Long.toString(Instant.now().getEpochSecond()),
                signature =
                        new RSASigner("PUB_KEY_ID_SYNTHETIC", PLATFORM.getPrivate())
                                .sign(
                                        timestamp
                                                + "\n"
                                                + "synthetic-signature-nonce"
                                                + "\n"
                                                + body
                                                + "\n")
                                .getSign();
        if (badSignature) signature = "AAAA";
        return new RequestParam.Builder()
                .serialNumber("PUB_KEY_ID_SYNTHETIC")
                .timestamp(timestamp)
                .nonce("synthetic-signature-nonce")
                .signature(signature)
                .body(body)
                .build();
    }

    PaymentResult complete(String ref, String tx) throws Exception {
        return service.callback("merchant-wx", notification(transaction(ref, tx), false, false));
    }

    <T> List<T> concurrent(int n, Callable<T> action) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        var gate = new CountDownLatch(1);
        var ready = new CountDownLatch(n);
        try {
            var jobs = new ArrayList<Future<T>>();
            for (int i = 0; i < n; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(gate.await(30, TimeUnit.SECONDS));
                                    return action.call();
                                }));
            assertTrue(ready.await(30, TimeUnit.SECONDS), "All workers must be ready before release");
            gate.countDown();
            var result = new ArrayList<T>();
            for (var job : jobs) result.add(job.get(60, TimeUnit.SECONDS));
            return result;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void officialRequestUsesSnapshotAndReturnsOnlyFiveParameters() {
        var parameters = pay();
        var a = attempt();
        assertEquals("PREPAY_CREATED", a.getStatus());
        assertNotNull(a.getPrepayRequestedAt());
        assertEquals(1, calls.get());
        var request = sent.get(0);
        assertEquals(a.getProviderOrderReference(), request.getOutTradeNo());
        assertNotEquals("order-A", request.getOutTradeNo());
        assertEquals(a.getAmountCents(), request.getAmount().getTotal().longValue());
        assertEquals(a.getAppid(), request.getAppid());
        assertEquals(a.getMerchantIdentity(), request.getMchid());
        assertEquals("synthetic-openid", request.getPayer().getOpenid());
        assertEquals(
                Set.of("timeStamp", "nonceStr", "package", "signType", "paySign"),
                parameters.keySet());
        assertEquals("RSA", parameters.get("signType"));
        assertEquals("prepay_id=synthetic-prepay", parameters.get("package"));
        var verifier =
                new RSAPublicKeyConfig.Builder()
                        .merchantId("synthetic-merchant")
                        .merchantSerialNumber("synthetic-serial")
                        .privateKey(MERCHANT.getPrivate())
                        .publicKey(MERCHANT.getPublic())
                        .publicKeyId("synthetic-serial")
                        .apiV3Key(UUID.randomUUID().toString().replace("-", ""))
                        .build()
                        .createVerifier();
        assertTrue(
                verifier.verify(
                        "synthetic-serial",
                        a.getAppid()
                                + "\n"
                                + parameters.get("timeStamp")
                                + "\n"
                                + parameters.get("nonceStr")
                                + "\n"
                                + parameters.get("package")
                                + "\n",
                        parameters.get("paySign")));
    }

    @Test
    void repeatedPayDoesNotResend() {
        pay();
        pay();
        assertEquals(1, calls.get());
        assertEquals(1, f.count("SELECT COUNT(*) FROM yshop_order_payment_attempt"));
    }

    @Test
    void twentyConcurrentPrepayRequestsOnlyOneTransportCall() throws Exception {
        var outcomes =
                concurrent(
                        20,
                        () -> {
                            try {
                                pay();
                                return "ok";
                            } catch (IllegalStateException e) {
                                assertEquals("WECHAT_PREPAY_RESULT_UNCERTAIN", e.getMessage());
                                return "uncertain";
                            }
                        });
        assertTrue(outcomes.contains("ok"));
        assertEquals(1, calls.get());
        assertEquals("PREPAY_CREATED", attempt().getStatus());
    }

    @Test
    void providerTimeoutRetainsActiveSlotAndCannotTerminateOrRepeat() {
        when(transport.execute(any(), eq(PrepayResponse.class)))
                .thenAnswer(
                        call -> {
                            calls.incrementAndGet();
                            throw new IllegalStateException("SYNTHETIC_TIMEOUT");
                        });
        assertThrows(IllegalStateException.class, this::pay);
        assertThrows(IllegalStateException.class, this::pay);
        assertEquals(1, calls.get());
        var a = attempt();
        assertEquals("CREATED", a.getStatus());
        assertNotNull(a.getPrepayRequestedAt());
        assertThrows(
                IllegalStateException.class,
                () -> attempts.terminateCreated(1L, a.getAttemptId(), PaymentAttemptState.FAILED));
        assertThrows(
                IllegalStateException.class,
                () ->
                        attempts.createOrGet(
                                1L,
                                "order-A",
                                PaymentSuccessEvent.Provider.WECHAT,
                                "merchant-wx",
                                "new-key"));
        f.effects(0);
    }

    @Test
    void malformedProviderResponseIsUncertain() {
        when(transport.execute(any(), eq(PrepayResponse.class)))
                .thenAnswer(
                        call -> {
                            calls.incrementAndGet();
                            HttpResponse<PrepayResponse> response = mock(HttpResponse.class);
                            when(response.getServiceResponse()).thenReturn(new PrepayResponse());
                            return response;
                        });
        assertThrows(IllegalStateException.class, this::pay);
        assertThrows(IllegalStateException.class, this::pay);
        assertEquals(1, calls.get());
        assertNull(attempt().getPrepayReference());
    }

    @ParameterizedTest
    @ValueSource(strings = {"appid", "mchid"})
    void mismatchedMerchantCannotSend(String field) {
        var other = mock(WechatV3Client.class);
        when(other.appid()).thenReturn(field.equals("appid") ? "other" : "synthetic-app");
        when(other.mchid()).thenReturn(field.equals("mchid") ? "other" : "synthetic-merchant");
        when(factory.forMerchant(anyString())).thenReturn(other);
        assertThrows(IllegalStateException.class, this::pay);
        assertEquals(0, calls.get());
        assertNull(attempt().getPrepayRequestedAt());
    }

    @Test
    void prepayCommitFailureReturnsNoPaymentParametersAndDoesNotRetryTransport() {
        f.jdbc.execute(
                "ALTER TABLE yshop_order_payment_attempt ADD CONSTRAINT injected_fault"
                        + " CHECK(status<>'PREPAY_CREATED')");
        assertThrows(RuntimeException.class, this::pay);
        assertNull(attempt().getPrepayReference());
        assertNotNull(attempt().getPrepayRequestedAt());
        f.dropFault("yshop_order_payment_attempt");
        assertThrows(IllegalStateException.class, this::pay);
        assertEquals(1, calls.get());
    }

    @Test
    void changedOrderAmountAfterPrepayRefusesReplay() {
        pay();
        f.jdbc.update("UPDATE yshop_store_order SET pay_price=0.03 WHERE id=1");
        assertThrows(IllegalStateException.class, this::pay);
        assertEquals(1, calls.get());
    }

    @Test
    void authenticatedNotificationCompletesAndReplayIsIdempotent() throws Exception {
        pay();
        var a = attempt();
        assertEquals(
                PaymentResult.FIRST_SUCCESS,
                complete(a.getProviderOrderReference(), "synthetic-T1"));
        assertEquals(
                PaymentResult.IDEMPOTENT_DUPLICATE,
                complete(a.getProviderOrderReference(), "synthetic-T1"));
        f.effects(1);
        assertEquals("PAID", attempts.read(1L, a.getAttemptId()).getStatus());
    }

    @Test
    void twentySignedNotificationsFulfillOnce() throws Exception {
        pay();
        var ref = attempt().getProviderOrderReference();
        var input = notification(transaction(ref, "synthetic-T1"), false, false);
        var result = concurrent(20, () -> service.callback("merchant-wx", input));
        assertEquals(1, Collections.frequency(result, PaymentResult.FIRST_SUCCESS));
        assertEquals(19, Collections.frequency(result, PaymentResult.IDEMPOTENT_DUPLICATE));
        f.effects(1);
    }

    @Test
    void badSignatureRejectedWithoutReceipt() throws Exception {
        var a = attempt();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.callback(
                                "merchant-wx",
                                notification(
                                        transaction(a.getProviderOrderReference(), "synthetic-T1"),
                                        true,
                                        false)));
        assertEquals(0, f.count("SELECT COUNT(*) FROM yshop_order_payment"));
        f.effects(0);
    }

    @Test
    void authenticatedBadCiphertextRejectedWithoutReceipt() throws Exception {
        var a = attempt();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.callback(
                                "merchant-wx",
                                notification(
                                        transaction(a.getProviderOrderReference(), "synthetic-T1"),
                                        false,
                                        true)));
        assertEquals(0, f.count("SELECT COUNT(*) FROM yshop_order_payment"));
        f.effects(0);
    }

    @Test
    void unknownReferenceCannotGuessBusinessOrder() throws Exception {
        assertEquals(PaymentResult.UNKNOWN_ORDER, complete("order-A", "synthetic-T1"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM yshop_order_payment"));
        f.effects(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"appid", "mchid", "merchant", "currency", "state", "type"})
    void invalidNotificationMetadataRejected(String field) throws Exception {
        var a = attempt();
        var data = transaction(a.getProviderOrderReference(), "synthetic-T1");
        if (field.equals("appid") || field.equals("mchid")) data.put(field, "other");
        if (field.equals("currency")) data.put("amount", Map.of("total", 2, "currency", "USD"));
        if (field.equals("state")) data.put("trade_state", "NOTPAY");
        if (field.equals("type")) data.put("trade_type", "NATIVE");
        var input = notification(data, false, false);
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.callback(
                                field.equals("merchant") ? "merchant-wx-2" : "merchant-wx", input));
        assertEquals(0, f.count("SELECT COUNT(*) FROM yshop_order_payment"));
        f.effects(0);
    }

    @Test
    void verifiedWrongAmountIsDurablyRejected() throws Exception {
        var a = attempt();
        var data = transaction(a.getProviderOrderReference(), "synthetic-T1");
        data.put("amount", Map.of("total", 3, "currency", "CNY"));
        assertEquals(
                PaymentResult.REJECTED,
                service.callback("merchant-wx", notification(data, false, false)));
        assertEquals(
                1,
                f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment WHERE"
                                + " status='PAYMENT_AMOUNT_MISMATCH'"));
        f.effects(0);
    }

    @Test
    void transactionCannotBindOtherAttempt() throws Exception {
        var a = attempt();
        f.order("order-B", 2);
        var b =
                attempts.createOrGet(
                        1L,
                        "order-B",
                        PaymentSuccessEvent.Provider.WECHAT,
                        "merchant-wx",
                        "wechat-v3-jsapi");
        assertEquals(
                PaymentResult.FIRST_SUCCESS,
                complete(a.getProviderOrderReference(), "synthetic-T1"));
        assertEquals(
                PaymentResult.REJECTED, complete(b.getProviderOrderReference(), "synthetic-T1"));
        assertEquals("CREATED", attempts.read(1L, b.getAttemptId()).getStatus());
        f.effects(1);
    }

    @Test
    void legacyBusinessOrderAndAttemptReferenceCannotCompleteV3Attempt() {
        pay();
        var a = attempt();
        assertEquals(PaymentResult.REJECTED, f.pay());
        assertEquals(
                PaymentResult.UNKNOWN_ORDER,
                f.service.accept(f.event(a.getProviderOrderReference(), "synthetic-T2", 2)));
        f.effects(0);
        assertEquals("PREPAY_CREATED", attempt().getStatus());
    }

    @Test
    void earlySuccessBeforePrepayPersistenceFailsSafe() throws Exception {
        when(transport.execute(any(), eq(PrepayResponse.class)))
                .thenAnswer(
                        call -> {
                            calls.incrementAndGet();
                            var a = attempt();
                            assertEquals(
                                    PaymentResult.FIRST_SUCCESS,
                                    complete(a.getProviderOrderReference(), "synthetic-T1"));
                            var response = new PrepayResponse();
                            response.setPrepayId("synthetic-prepay");
                            HttpResponse<PrepayResponse> http = mock(HttpResponse.class);
                            when(http.getServiceResponse()).thenReturn(response);
                            return http;
                        });
        assertThrows(IllegalStateException.class, this::pay);
        assertEquals(1, calls.get());
        assertEquals(
                1,
                f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE status='PAID' AND"
                                + " prepay_reference IS NULL"));
        f.effects(1);
    }

    @Test
    void callbackFinalizationRollbackRecovers() throws Exception {
        pay();
        var a = attempt();
        f.jdbc.execute(
                "ALTER TABLE yshop_order_payment ADD CONSTRAINT injected_fault"
                        + " CHECK(status<>'SUCCESS')");
        assertEquals(PaymentResult.RETRY, complete(a.getProviderOrderReference(), "synthetic-T1"));
        assertEquals("PREPAY_CREATED", attempt().getStatus());
        assertNull(attempt().getProviderTransactionId());
        f.effects(0);
        f.dropFault("yshop_order_payment");
        f.service.recover();
        f.effects(1);
        assertEquals("PAID", attempts.read(1L, a.getAttemptId()).getStatus());
    }

    @Test
    void uncertaintyMarkerCannotBeClearedOrTerminatedViaSqlOnMysql() {
        attempt();
        var a = attempt();
        assertTrue(attempts.claimPrepay(1L, a.getAttemptId()));
        assertFalse(attempts.claimPrepay(1L, a.getAttemptId()));
        if (PaymentDatabaseTest.mysqlAcceptance()) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.jdbc.update(
                                    "UPDATE yshop_order_payment_attempt SET"
                                            + " prepay_requested_at=NULL WHERE attempt_id=?",
                                    a.getAttemptId()));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.jdbc.update(
                                    "UPDATE yshop_order_payment_attempt SET status='FAILED' WHERE"
                                            + " attempt_id=?",
                                    a.getAttemptId()));
        }
        assertNotNull(attempt().getPrepayRequestedAt());
    }

    @Test
    void noCallerTransactionAcrossProviderIo() {
        var tx = new TransactionTemplate(f.ctx.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> assertThrows(IllegalStateException.class, this::pay));
        assertEquals(0, calls.get());
    }

    org.springframework.test.web.servlet.MockMvc mvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new co.yixiang.yshop.module.order.controller.app.order
                                .WechatV3CallbackController(service))
                .build();
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder httpNotification(
            RequestParam p) {
        String[] parts = p.getMessage().split("\n", 3);
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/order/notify/wechat-v3/merchant-wx")
                .contentType("application/json")
                .content(p.getBody())
                .header("Wechatpay-Serial", p.getSerialNumber())
                .header("Wechatpay-Timestamp", parts[0])
                .header("Wechatpay-Nonce", parts[1])
                .header("Wechatpay-Signature", p.getSignature());
    }

    @Test
    void httpVerifiedCallback204AndReplay204() throws Exception {
        var a = attempt();
        var input =
                notification(
                        transaction(a.getProviderOrderReference(), "synthetic-T1"), false, false);
        mvc().perform(httpNotification(input))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNoContent());
        mvc().perform(httpNotification(input))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNoContent());
        f.effects(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "signature", "decryption", "timestamp"})
    void httpInvalidCallback400NoReceipt(String cause) throws Exception {
        var a = attempt();
        var input =
                notification(
                        transaction(a.getProviderOrderReference(), "synthetic-T1"),
                        cause.equals("signature"),
                        cause.equals("decryption"));
        var request = httpNotification(input);
        if (cause.equals("missing"))
            request =
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/order/notify/wechat-v3/merchant-wx")
                            .contentType("application/json")
                            .content(input.getBody());
        if (cause.equals("timestamp")) {
            String nonce = input.getMessage().split("\n", 3)[1];
            String signature =
                    new RSASigner("PUB_KEY_ID_SYNTHETIC", PLATFORM.getPrivate())
                            .sign("1\n" + nonce + "\n" + input.getBody() + "\n")
                            .getSign();
            request =
                    httpNotification(
                            new RequestParam.Builder()
                                    .serialNumber(input.getSerialNumber())
                                    .timestamp("1")
                                    .nonce(nonce)
                                    .signature(signature)
                                    .body(input.getBody())
                                    .build());
        }
        mvc().perform(request)
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        assertEquals(0, f.count("SELECT COUNT(*) FROM yshop_order_payment"));
        f.effects(0);
    }

    @Test
    void httpUnknownAttemptRetriesWithoutFulfillment() throws Exception {
        mvc().perform(
                        httpNotification(
                                notification(
                                        transaction("unknown-reference", "synthetic-T1"),
                                        false,
                                        false)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isServiceUnavailable());
        f.effects(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "invalid"})
    void officialHttpClientSignsRequestAndVerifiesFakeResponse(String signature) throws Exception {
        // Application interceptor is terminal: never proceeds to DNS/socket/network.
        var fakeHttp =
                new okhttp3.OkHttpClient.Builder()
                        .retryOnConnectionFailure(false)
                        .addInterceptor(
                                chain -> {
                                    calls.incrementAndGet();
                                    assertNotNull(chain.request().header("Authorization"));
                                    assertTrue(
                                            chain.request()
                                                    .header("Authorization")
                                                    .contains("synthetic-serial"));
                                    String response = "{\"prepay_id\":\"synthetic-prepay\"}",
                                            timestamp =
                                                    Long.toString(Instant.now().getEpochSecond()),
                                            nonce = "synthetic-response-nonce";
                                    String sign =
                                            new RSASigner(
                                                            "PUB_KEY_ID_SYNTHETIC",
                                                            PLATFORM.getPrivate())
                                                    .sign(
                                                            timestamp + "\n" + nonce + "\n"
                                                                    + response + "\n")
                                                    .getSign();
                                    return new okhttp3.Response.Builder()
                                            .request(chain.request())
                                            .protocol(okhttp3.Protocol.HTTP_1_1)
                                            .code(200)
                                            .message("OK")
                                            .header("Wechatpay-Serial", "PUB_KEY_ID_SYNTHETIC")
                                            .header("Wechatpay-Timestamp", timestamp)
                                            .header("Wechatpay-Nonce", nonce)
                                            .header(
                                                    "Wechatpay-Signature",
                                                    signature.equals("valid") ? sign : "AAAA")
                                            .body(
                                                    okhttp3.ResponseBody.create(
                                                            okhttp3.MediaType.parse(
                                                                    "application/json"),
                                                            response))
                                            .build();
                                })
                        .build();
        var officialTransport =
                new DefaultHttpClientBuilder()
                        .config(config)
                        .okHttpClient(fakeHttp)
                        .disableRetryOnConnectionFailure()
                        .build();
        client =
                new OfficialWechatV3Client(
                        "synthetic-app",
                        "synthetic-merchant",
                        "https://synthetic.invalid/app-api/order/notify/wechat-v3/merchant-wx",
                        config,
                        officialTransport);
        when(factory.forMerchant(anyString())).thenReturn(client);
        if (signature.equals("valid")) {
            assertEquals(5, pay().size());
            assertEquals("PREPAY_CREATED", attempt().getStatus());
        } else {
            assertThrows(IllegalStateException.class, this::pay);
            assertThrows(IllegalStateException.class, this::pay);
            assertEquals("CREATED", attempt().getStatus());
            assertNull(attempt().getPrepayReference());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void v3MigrationRerunPreservesUncertaintyMarker() throws Exception {
        var a = attempt();
        assertTrue(attempts.claimPrepay(1L, a.getAttemptId()));
        var before = attempts.read(1L, a.getAttemptId()).getPrepayRequestedAt();
        if (PaymentDatabaseTest.mysqlAcceptance()) {
            var process =
                    new ProcessBuilder(
                                    "python3",
                                    "../../../tests/payment/mysql-acceptance.py",
                                    "--install-v3-migration")
                            .redirectErrorStream(true)
                            .start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
        }
        assertEquals(before, attempts.read(1L, a.getAttemptId()).getPrepayRequestedAt());
        assertFalse(attempts.claimPrepay(1L, a.getAttemptId()));
        assertEquals(1, f.count("SELECT COUNT(*) FROM yshop_order_payment_attempt"));
    }

    @Test
    void legacyGuardUsesCurrentReadDespiteOuterRepeatableReadSnapshot() throws Exception {
        var tx = new TransactionTemplate(f.ctx.getBean(PlatformTransactionManager.class));
        // InnoDB locking reads bypass an earlier repeatable-read snapshot. H2's snapshot
        // implementation differs; exercise the concurrent guard there at read committed.
        tx.setIsolationLevel(
                PaymentDatabaseTest.mysqlAcceptance()
                        ? org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ
                        : org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(
                status -> {
                    assertEquals(0, f.count("SELECT COUNT(*) FROM yshop_order_payment_attempt"));
                    var pool = Executors.newSingleThreadExecutor();
                    try {
                        pool.submit(this::attempt).get(60, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("SYNTHETIC_CONCURRENT_CREATION_FAILED");
                    } finally {
                        pool.shutdownNow();
                    }
                    assertEquals(PaymentResult.REJECTED, f.pay());
                });
        f.effects(0);
        assertEquals(0, f.count("SELECT paid FROM yshop_store_order WHERE id=1"));
        assertEquals(
                1,
                f.count(
                        "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS"
                            + " NOT NULL"));
    }
}
