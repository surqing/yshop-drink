package co.yixiang.yshop.module.pay.preflight;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.module.pay.credential.PaymentCredentialCryptoService;
import co.yixiang.yshop.module.pay.dal.dataobject.merchantdetails.MerchantDetailsDO;
import co.yixiang.yshop.module.pay.dal.mysql.merchantdetails.MerchantDetailsMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.*;
import java.time.*;
import java.util.*;

class LivePaymentPreflightTest {
    static final KeyPair MERCHANT = key(), PLATFORM = key();

    static KeyPair key() {
        try {
            var k = KeyPairGenerator.getInstance("RSA");
            k.initialize(2048);
            return k.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("SYNTHETIC_SETUP_FAILED");
        }
    }

    static String pem(Key k, String label) {
        return "-----BEGIN "
                + label
                + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(k.getEncoded())
                + "\n-----END "
                + label
                + "-----";
    }

    PaymentCredentialCryptoService crypto;
    MerchantDetailsDO merchant;
    LivePaymentPreflightService service;
    LivePaymentAuditService audit;
    String privateKey, apiKey;
    Instant now = Instant.now();

    @BeforeEach
    void setup() {
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        crypto = new PaymentCredentialCryptoService(Base64.getEncoder().encodeToString(master));
        privateKey = pem(MERCHANT.getPrivate(), "PRIVATE KEY");
        apiKey = UUID.randomUUID().toString().replace("-", "");
        merchant =
                MerchantDetailsDO.builder()
                        .detailsId("synthetic")
                        .payType("wxPay")
                        .isTest(0)
                        .wechatApiVersion("V3")
                        .appid("synthetic-app")
                        .mchId("synthetic-merchant")
                        .merchantCertificateSerial("ABC123")
                        .platformPublicKeyId("PUB_KEY_ID_SYNTHETIC")
                        .keyPublic(pem(PLATFORM.getPublic(), "PUBLIC KEY"))
                        .notifyUrl(
                                "https://synthetic.invalid/app-api/order/notify/wechat-v3/synthetic")
                        .keyPrivate(crypto.encrypt("synthetic", "keyPrivate", privateKey))
                        .apiV3Key(crypto.encrypt("synthetic", "apiV3Key", apiKey))
                        .build();
        var mapper = mock(MerchantDetailsMapper.class);
        when(mapper.selectById("synthetic")).thenReturn(merchant);
        audit = mock(LivePaymentAuditService.class);
        when(audit.schemaComplete()).thenReturn(true);
        when(audit.snapshot())
                .thenReturn(new LivePaymentAuditService.Snapshot(true, Map.of(), now));
        when(audit.databaseOffsetMillis(any())).thenReturn(0L);
        service =
                new LivePaymentPreflightService(
                        mapper, crypto, audit, Clock.fixed(now, ZoneOffset.UTC));
        for (String field : List.of("live", "recovery", "ingressVerified", "historyReviewed"))
            ReflectionTestUtils.setField(service, field, true);
        ReflectionTestUtils.setField(service, "clockObservedAt", now.toString());
        ReflectionTestUtils.setField(service, "ntpOffset", "0");
    }

    LivePaymentPreflightService.Report check() {
        return service.check("synthetic");
    }

    @Test
    void completeSyntheticConfigOfflinePassAndNoLeak() throws Exception {
        try (var http = mockConstruction(DefaultHttpClientBuilder.class);
                var sdk = mockConstruction(JsapiService.class)) {
            var r = check();
            assertTrue(r.configurationReady());
            assertTrue(r.liveReady());
            assertTrue(http.constructed().isEmpty());
            assertTrue(sdk.constructed().isEmpty());
            String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(r);
            for (String secret :
                    List.of(
                            privateKey,
                            apiKey,
                            merchant.getKeyPrivate(),
                            merchant.getApiV3Key(),
                            merchant.getKeyPublic())) {
                assertFalse(json.contains(secret));
                assertFalse(r.toString().contains(secret));
            }
            assertFalse(json.contains("BEGIN PRIVATE KEY"));
            assertFalse(json.contains("enc:v1:"));
        }
    }

    @Test
    void missingPrivateKeyFails() {
        merchant.setKeyPrivate(null);
        assertFalse(check().configurationReady());
        assertFalse(check().checks().get("PRIVATE_KEY_ENCRYPTED"));
    }

    @Test
    void missingApiV3Fails() {
        merchant.setApiV3Key(null);
        assertFalse(check().liveReady());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://synthetic.invalid/app-api/order/notify/wechat-v3/synthetic",
                "https://synthetic.invalid/wrong",
                "https://synthetic.invalid/app-api/order/notify/wechat-v3/other",
                "https://user@synthetic.invalid/app-api/order/notify/wechat-v3/synthetic",
                "https://synthetic.invalid/app-api/order/notify/wechat-v3/synthetic?key=sensitive",
                "https://synthetic.invalid/app-api/order/notify/wechat-v3/synthetic#x",
                "https://localhost/app-api/order/notify/wechat-v3/synthetic",
                "https://synthetic.invalid:8443/app-api/order/notify/wechat-v3/synthetic"
            })
    void invalidNotifyFails(String url) {
        merchant.setNotifyUrl(url);
        assertFalse(check().checks().get("NOTIFY_URL"));
        assertFalse(check().liveReady());
    }

    @Test
    void missingIdentityFails() {
        merchant.setMchId(" ");
        assertFalse(check().configurationReady());
    }

    @Test
    void wrongMasterKeyFailsSafely() {
        byte[] wrong = new byte[32];
        new SecureRandom().nextBytes(wrong);
        ReflectionTestUtils.setField(
                service,
                "crypto",
                new PaymentCredentialCryptoService(Base64.getEncoder().encodeToString(wrong)));
        assertFalse(check().checks().get("MASTER_KEY_DECRYPTION"));
        assertFalse(check().liveReady());
    }

    @Test
    void unencryptedOrMalformedCredentialsFail() {
        merchant.setKeyPrivate(privateKey);
        assertFalse(check().liveReady());
        merchant.setKeyPrivate("enc:v1:broken");
        assertFalse(check().liveReady());
    }

    @Test
    void wrongApiKeyLengthFails() {
        merchant.setApiV3Key(crypto.encrypt("synthetic", "apiV3Key", "synthetic-short"));
        assertFalse(check().liveReady());
    }

    @Test
    void malformedPublicKeyFails() {
        merchant.setKeyPublic("synthetic-invalid-public");
        assertFalse(check().checks().get("OFFLINE_SDK_CONFIG"));
    }

    @Test
    void legacyGateOffFailsAndRecoveryRelationshipDetected() {
        ReflectionTestUtils.setField(service, "live", false);
        var r = check();
        assertFalse(r.liveReady());
        assertFalse(r.checks().get("LEGACY_EXTERNAL_LIVE_GATE"));
        assertFalse(r.checks().get("CONFIGURATION_RELATIONSHIP"));
    }

    @Test
    void unknownOrStaleClockFails() {
        ReflectionTestUtils.setField(service, "clockObservedAt", "");
        assertFalse(check().liveReady());
        ReflectionTestUtils.setField(service, "clockObservedAt", now.minusSeconds(301).toString());
        assertFalse(check().liveReady());
        ReflectionTestUtils.setField(service, "clockObservedAt", now.toString());
        ReflectionTestUtils.setField(service, "ntpOffset", "2000");
        assertFalse(check().liveReady());
    }

    @Test
    void ingressAndHistoryRequireExplicitEvidence() {
        ReflectionTestUtils.setField(service, "ingressVerified", false);
        assertFalse(check().liveReady());
        ReflectionTestUtils.setField(service, "ingressVerified", true);
        ReflectionTestUtils.setField(service, "historyReviewed", false);
        assertFalse(check().liveReady());
    }

    @Test
    void missingSchemaOrUnavailableAuditFails() {
        when(audit.schemaComplete()).thenReturn(false);
        assertFalse(check().liveReady());
        when(audit.schemaComplete()).thenReturn(true);
        when(audit.snapshot())
                .thenReturn(new LivePaymentAuditService.Snapshot(false, Map.of(), now));
        assertFalse(check().liveReady());
    }

    @Test
    void conflictAndUncertainFailReadiness() {
        for (String risk :
                List.of("UNCERTAIN_ATTEMPTS", "PAYMENT_CONFLICT", "RECONCILIATION_REQUIRED")) {
            when(audit.snapshot())
                    .thenReturn(new LivePaymentAuditService.Snapshot(true, Map.of(risk, 1L), now));
            assertFalse(check().liveReady());
        }
    }

    @Test
    void historicalEvidenceRequiresReviewButSettledReceiptsAreNotPermanentBlock() {
        when(audit.snapshot())
                .thenReturn(
                        new LivePaymentAuditService.Snapshot(
                                true, Map.of("PRE_ATTEMPT_EXTERNAL_RECEIPTS", 2L), now));
        assertTrue(check().liveReady());
        ReflectionTestUtils.setField(service, "historyReviewed", false);
        assertFalse(check().liveReady());
    }

    @ParameterizedTest
    @ValueSource(strings = {"appid", "serial", "platformId", "publicKey"})
    void missingMerchantMetadataFails(String field) {
        switch (field) {
            case "appid" -> merchant.setAppid(null);
            case "serial" -> merchant.setMerchantCertificateSerial(null);
            case "platformId" -> merchant.setPlatformPublicKeyId(null);
            case "publicKey" -> merchant.setKeyPublic(null);
        }
        assertFalse(check().configurationReady());
    }

    @Test
    void existingGatePropertiesInjectedWithoutEnablingPaymentClients() {
        var mapper = mock(MerchantDetailsMapper.class);
        when(mapper.selectById("synthetic")).thenReturn(merchant);
        try (var ctx =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new org.springframework.core.env.MapPropertySource(
                                    "synthetic",
                                    Map.of(
                                            "yshop.pay.wechat-v3.enabled", "true",
                                            "yshop.pay.wechat-v3.reconciliation-enabled", "true",
                                            "yshop.pay.preflight.ingress-verified", "true",
                                            "yshop.pay.preflight.history-reviewed", "true",
                                            "yshop.pay.preflight.clock.observed-at",
                                                    Instant.now().toString(),
                                            "yshop.pay.preflight.clock.ntp-offset-millis", "0")));
            ctx.registerBean(
                    LivePaymentPreflightService.class,
                    () -> new LivePaymentPreflightService(mapper, crypto, audit));
            ctx.refresh();
            assertTrue(
                    ctx.getBean(LivePaymentPreflightService.class).check("synthetic").liveReady());
        }
    }

    @Test
    void deletedLegacyOrTestMerchantFail() {
        merchant.setDeleted(true);
        assertFalse(check().configurationReady());
        merchant.setDeleted(false);
        merchant.setWechatApiVersion("V2");
        assertFalse(check().configurationReady());
        merchant.setWechatApiVersion("V3");
        merchant.setIsTest(1);
        assertFalse(check().configurationReady());
    }
}
