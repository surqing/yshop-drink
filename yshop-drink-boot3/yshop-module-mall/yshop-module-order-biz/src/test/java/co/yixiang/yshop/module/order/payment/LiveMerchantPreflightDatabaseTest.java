package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.yixiang.yshop.module.order.service.payment.PaymentInbox;
import co.yixiang.yshop.module.order.service.payment.attempt.PaymentAttemptState;
import co.yixiang.yshop.module.pay.credential.PaymentCredentialCryptoService;
import co.yixiang.yshop.module.pay.dal.mysql.merchantdetails.MerchantDetailsMapper;
import co.yixiang.yshop.module.pay.preflight.*;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.*;
import java.time.Instant;
import java.util.*;

/** Real production read-only diagnostics; MySQL profile uses all actual migrations/guards. */
class LiveMerchantPreflightDatabaseTest {
    WechatV3DatabaseTest v;
    LivePaymentAuditService audit;
    LivePaymentPreflightService preflight;

    @BeforeEach
    void setup() throws Exception {
        v = new WechatV3DatabaseTest();
        v.setup();
        var j = v.f.jdbc;
        j.execute("DROP TABLE merchant_details");
        j.execute(
                "CREATE TABLE merchant_details(details_id VARCHAR(32) PRIMARY KEY,pay_type"
                    + " VARCHAR(16),appid VARCHAR(64),mch_id VARCHAR(64),seller VARCHAR(64),is_test"
                    + " INT,deleted BOOLEAN DEFAULT FALSE,wechat_api_version"
                    + " VARCHAR(8),merchant_certificate_serial VARCHAR(64),platform_public_key_id"
                    + " VARCHAR(64),key_public MEDIUMTEXT,key_private MEDIUMTEXT,api_v3_key"
                    + " MEDIUMTEXT,notify_url VARCHAR(256),return_url VARCHAR(256),cert_store_type"
                    + " VARCHAR(16),key_cert MEDIUMTEXT,key_cert_pwd MEDIUMTEXT,sign_type"
                    + " VARCHAR(16),sub_app_id VARCHAR(32),sub_mch_id VARCHAR(32),input_charset"
                    + " VARCHAR(16),creator VARCHAR(64),updater VARCHAR(64),create_time TIMESTAMP"
                    + " DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
                        + (PaymentDatabaseTest.mysqlAcceptance() ? " ENGINE=InnoDB" : ""));
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        var crypto = new PaymentCredentialCryptoService(Base64.getEncoder().encodeToString(master));
        j.update(
                "INSERT INTO"
                    + " merchant_details(details_id,pay_type,appid,mch_id,is_test,wechat_api_version,merchant_certificate_serial,platform_public_key_id,key_public,key_private,api_v3_key,notify_url)"
                    + " VALUES('merchant-wx','wxPay','synthetic-app','synthetic-merchant',0,'V3','ABC123','PUB_KEY_ID_SYNTHETIC',?,?,?,?)",
                pem(WechatV3DatabaseTest.PLATFORM.getPublic(), "PUBLIC KEY"),
                crypto.encrypt(
                        "merchant-wx",
                        "keyPrivate",
                        pem(WechatV3DatabaseTest.MERCHANT.getPrivate(), "PRIVATE KEY")),
                crypto.encrypt(
                        "merchant-wx", "apiV3Key", UUID.randomUUID().toString().replace("-", "")),
                "https://synthetic.invalid/app-api/order/notify/wechat-v3/merchant-wx");
        var factory = v.f.ctx.getBean(SqlSessionFactory.class);
        if (!factory.getConfiguration().hasMapper(MerchantDetailsMapper.class))
            factory.getConfiguration().addMapper(MerchantDetailsMapper.class);
        var merchants = new SqlSessionTemplate(factory).getMapper(MerchantDetailsMapper.class);
        audit = new LivePaymentAuditService(j);
        preflight = new LivePaymentPreflightService(merchants, crypto, audit);
        for (String field : List.of("live", "recovery", "ingressVerified", "historyReviewed"))
            ReflectionTestUtils.setField(preflight, field, true);
        ReflectionTestUtils.setField(preflight, "clockObservedAt", Instant.now().toString());
        ReflectionTestUtils.setField(preflight, "ntpOffset", "0");
    }

    static String pem(Key key, String label) {
        return "-----BEGIN "
                + label
                + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
                + "\n-----END "
                + label
                + "-----";
    }

    @AfterEach
    void close() {
        if (v != null) v.close();
    }

    Map<String, List<Map<String, Object>>> fingerprint() {
        var result = new TreeMap<String, List<Map<String, Object>>>();
        for (String t :
                List.of(
                        "merchant_details",
                        "yshop_order_payment_attempt",
                        "yshop_order_payment",
                        "yshop_order_payment_conflict",
                        "yshop_store_order",
                        "yshop_user",
                        "yshop_member_wallet_transaction",
                        "yshop_member_recharge_order"))
            result.put(t, v.f.jdbc.queryForList("SELECT * FROM " + t));
        return result;
    }

    @Test
    void syntheticMerchantAndSchemaChecksReadOnly() {
        var before = fingerprint();
        var r = preflight.check("merchant-wx");
        assertTrue(r.checks().get("OFFLINE_SDK_CONFIG"));
        assertTrue(r.audit().available());
        assertEquals(PaymentDatabaseTest.mysqlAcceptance(), r.checks().get("DATABASE_SCHEMA"));
        assertEquals(PaymentDatabaseTest.mysqlAcceptance(), r.liveReady());
        assertEquals(before, fingerprint());
        assertEquals(0, v.calls.get());
    }

    @Test
    void uncertainAndConflictsDetectedWithoutMutation() throws Exception {
        var a = v.attempt();
        assertTrue(v.attempts.claimPrepay(1L, a.getAttemptId()));
        var inbox = v.f.ctx.getBean(PaymentInbox.class);
        var event = inbox.capture(v.f.event("order-A", "synthetic-conflict", 2));
        v.f.jdbc.update(
                "UPDATE yshop_order_payment SET status='PAYMENT_CONFLICT' WHERE id=?",
                event.id());
        var before = fingerprint();
        var r = preflight.check("merchant-wx");
        assertEquals(1L, r.audit().counts().get("UNCERTAIN_ATTEMPTS"));
        assertEquals(1L, r.audit().counts().get("PAYMENT_CONFLICT"));
        assertFalse(r.liveReady());
        assertEquals(before, fingerprint());
        assertEquals(0, v.calls.get());
    }

    @Test
    void terminalLateSuccessAndHistoricalRiskDetectedReadOnly() throws Exception {
        var a = v.attempt();
        v.attempts.terminateCreated(1L, a.getAttemptId(), PaymentAttemptState.CANCELED);
        v.complete(a.getProviderOrderReference(), "synthetic-late");
        v.f.ctx.getBean(PaymentInbox.class).capture(v.f.event("order-history", "synthetic-old", 2));
        v.f.order("historical-unpaid", 2L);
        v.f.jdbc.update(
                "UPDATE yshop_store_order SET pay_type='alipay' WHERE"
                    + " order_id='historical-unpaid'");
        var before = fingerprint();
        var r = audit.snapshot();
        assertEquals(1L, r.counts().get("TERMINAL_LATE_SUCCESS"));
        assertEquals(1L, r.counts().get("RECONCILIATION_REQUIRED"));
        assertEquals(1L, r.counts().get("PRE_ATTEMPT_EXTERNAL_RECEIPTS"));
        assertEquals(1L, r.counts().get("LEGACY_EXTERNAL_UNPAID_CANDIDATES"));
        assertEquals(before, fingerprint());
        v.f.effects(0);
        assertEquals(0, v.calls.get());
    }

    @Test
    void missingIndexMakesSchemaFailClosed() {
        if (PaymentDatabaseTest.mysqlAcceptance()) {
            assertTrue(audit.schemaComplete());
            v.f.jdbc.execute(
                    "ALTER TABLE yshop_order_payment_attempt DROP INDEX uk_attempt_active");
        }
        assertFalse(audit.schemaComplete());
        assertFalse(preflight.check("merchant-wx").liveReady());
    }

    @Test
    void rawCallbackIngressSyntheticSignatureAndReplay() throws Exception {
        var a = v.attempt();
        var input =
                v.notification(
                        v.transaction(a.getProviderOrderReference(), "synthetic-ingress"),
                        false,
                        false);
        v.mvc().perform(v.httpNotification(input)).andExpect(status().isNoContent());
        v.mvc().perform(v.httpNotification(input)).andExpect(status().isNoContent());
        v.f.effects(1);
        assertEquals(0, v.calls.get());
    }

    @Test
    void modifiedRawBodyAndStrippedHeaderRejected() throws Exception {
        var a = v.attempt();
        var input =
                v.notification(
                        v.transaction(a.getProviderOrderReference(), "synthetic-ingress"),
                        false,
                        false);
        v.mvc()
                .perform(v.httpNotification(input).content(input.getBody() + " "))
                .andExpect(status().isBadRequest());
        var missing = v.httpNotification(input);
        missing.with(
                r -> {
                    r.removeHeader("Wechatpay-Serial");
                    return r;
                });
        v.mvc().perform(missing).andExpect(status().isBadRequest());
        v.f.effects(0);
        assertEquals(0, v.f.count("SELECT COUNT(*) FROM yshop_order_payment"));
    }
}
