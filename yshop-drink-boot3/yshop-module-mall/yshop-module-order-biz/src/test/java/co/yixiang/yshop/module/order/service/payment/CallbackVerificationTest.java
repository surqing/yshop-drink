package co.yixiang.yshop.module.order.service.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.module.pay.callback.*;
import co.yixiang.yshop.module.pay.config.handlers.*;

import com.egzosn.pay.ali.api.*;
import com.egzosn.pay.spring.boot.core.PayServiceManager;
import com.egzosn.pay.wx.api.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Real SDK verification, synthetic keys only; no HTTP clients or payment calls. */
class CallbackVerificationTest {
    PaymentCallbackService callbacks;
    WxPayService wx;
    WxPayMessageHandler handler;
    Map<String, Object> body;

    @BeforeEach
    void setup() {
        callbacks = mock(PaymentCallbackService.class);
        when(callbacks.accept(any())).thenReturn(PaymentResult.FIRST_SUCCESS);
        var config = new WxPayConfigStorage();
        config.setAppid("synthetic-app");
        config.setMchId("synthetic-merchant");
        config.setKeyPrivate(UUID.randomUUID().toString().replace("-", ""));
        config.setSignType("MD5");
        config.setInputCharset("UTF-8");
        config.setTest(false);
        wx = new WxPayService(config);
        handler = new WxPayMessageHandler(callbacks);
        body = new TreeMap<>();
        body.put("return_code", "SUCCESS");
        body.put("result_code", "SUCCESS");
        body.put("appid", "synthetic-app");
        body.put("mch_id", "synthetic-merchant");
        body.put("out_trade_no", "synthetic-order");
        body.put("transaction_id", "synthetic-transaction");
        body.put("total_fee", "2");
        body.put("fee_type", "CNY");
        sign();
    }

    void sign() {
        body.remove("sign");
        body.put(
                "sign",
                wx.createSign(
                        com.egzosn.pay.common.util.sign.SignTextUtils.parameterText(
                                body, "&", "sign", "appId"),
                        "UTF-8"));
    }

    String handle() {
        return handler.handleCallback("synthetic", body, wx).toMessage();
    }

    void fail() {
        assertTrue(handle().contains("FAIL"));
        verify(callbacks, never()).accept(any());
    }

    @Test
    void actualSdkValidSignatureAndSafeEvent() {
        assertTrue(wx.verify(body));
        assertTrue(handle().contains("SUCCESS"));
        var captor = org.mockito.ArgumentCaptor.forClass(PaymentSuccessEvent.class);
        verify(callbacks).accept(captor.capture());
        assertEquals(2, captor.getValue().totalFeeCents());
        assertEquals("synthetic", captor.getValue().merchantDetailsId());
        assertFalse(captor.getValue().toString().contains("sign="));
    }

    @Test
    void actualSdkHmacSignatureAccepted() {
        wx.getPayConfigStorage().setSignType("HMACSHA256");
        body.put("sign_type", "HMAC-SHA256");
        sign();
        assertTrue(wx.verify(body));
        assertTrue(handle().contains("SUCCESS"));
        verify(callbacks).accept(any());
    }

    @Test
    void actualSdkReqInfoBypassIsBlocked() {
        var refundLike = new TreeMap<String, Object>(body);
        refundLike.remove("sign");
        refundLike.put("req_info", "synthetic");
        assertTrue(
                wx.verify(refundLike)); // Evidence of SDK branch; not trusted by our entry point.
        body = refundLike;
        fail();
    }

    @Test
    void unsignedRejected() {
        body.remove("sign");
        fail();
    }

    @Test
    void invalidSignatureRejected() {
        body.put("sign", "0".repeat(32));
        fail();
    }

    @Test
    void alteredAmountRejected() {
        body.put("total_fee", "3");
        fail();
    }

    @ParameterizedTest
    @ValueSource(strings = {"appid", "mch_id"})
    void wrongMerchantOrApp(String key) {
        body.put(key, "other-synthetic");
        sign();
        fail();
    }

    @ParameterizedTest
    @ValueSource(strings = {"return_code", "result_code"})
    void nonSuccessRejected(String key) {
        body.put(key, "FAIL");
        sign();
        fail();
    }

    @Test
    void currencyRejected() {
        body.put("fee_type", "USD");
        sign();
        fail();
    }

    @Test
    void noSandboxKeyHttpRequest() {
        wx.getPayConfigStorage().setTest(true);
        fail();
    }

    @Test
    void legacySdkHandlerFailsClosed() {
        assertTrue(handler.handle(null, Map.of(), wx).toMessage().contains("FAIL"));
        verifyNoInteractions(callbacks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "NaN", "9223372036854775808"})
    void invalidCents(String amount) {
        body.put("total_fee", amount);
        sign();
        assertThrows(RuntimeException.class, this::handle);
        verifyNoInteractions(callbacks);
    }

    @Test
    void exactMoneyNoRoundingOrOverflow() {
        assertEquals(10, Money.cents(new BigDecimal("0.10")));
        assertEquals(2, Money.cents(new BigDecimal("0.0200")));
        assertThrows(ArithmeticException.class, () -> Money.cents(new BigDecimal("0.001")));
        assertThrows(
                ArithmeticException.class,
                () -> Money.cents(new BigDecimal("922337203685477580.8")));
        assertThrows(IllegalArgumentException.class, () -> Money.cents(BigDecimal.ZERO));
    }

    @Test
    void retryRequiresProviderFail() {
        when(callbacks.accept(any())).thenReturn(PaymentResult.RETRY);
        assertTrue(handle().contains("FAIL"));
        when(callbacks.accept(any())).thenReturn(PaymentResult.UNKNOWN_ORDER);
        assertTrue(handle().contains("FAIL"));
    }

    @Test
    void duplicateAcknowledged() {
        when(callbacks.accept(any())).thenReturn(PaymentResult.IDEMPOTENT_DUPLICATE);
        assertTrue(handle().contains("SUCCESS"));
    }

    @Test
    void xmlCannotLoadEntitiesOrDuplicateFields() throws Exception {
        assertEquals(
                "SUCCESS",
                VerifiedPaymentCallback.parseXml(
                                "<xml><result_code><![CDATA[SUCCESS]]></result_code></xml>"
                                        .getBytes())
                        .get("result_code"));
        assertThrows(
                Exception.class,
                () ->
                        VerifiedPaymentCallback.parseXml(
                                "<!DOCTYPE xml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><xml><a>&x;</a></xml>"
                                        .getBytes()));
        assertThrows(
                Exception.class,
                () -> VerifiedPaymentCallback.parseXml("<xml><a>1</a><a>2</a></xml>".getBytes()));
    }

    @Test
    void gatewayNeverLogsRawInvalidCallbackEvenAtDebug() {
        var manager = mock(PayServiceManager.class);
        when(manager.cast(eq("synthetic"), eq(com.egzosn.pay.common.api.PayService.class)))
                .thenReturn(wx);
        var gateway =
                new VerifiedPaymentCallback(manager, handler, new AliPayMessageHandler(callbacks));
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(VerifiedPaymentCallback.class);
        var sdkLogger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(
                                com.egzosn.pay.common.api.BasePayService.class);
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        sdkLogger.addAppender(appender);
        var old = sdkLogger.getLevel();
        sdkLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            var request = new MockHttpServletRequest();
            request.setContent(
                    "<xml><return_code>SUCCESS</return_code><result_code>SUCCESS</result_code><openid>synthetic-private-marker</openid></xml>"
                            .getBytes(StandardCharsets.UTF_8));
            assertTrue(gateway.receive("synthetic", request).contains("FAIL"));
            var malformed = new MockHttpServletRequest();
            malformed.setContent("<xml>synthetic-private-marker".getBytes());
            assertTrue(gateway.receive("synthetic", malformed).contains("FAIL"));
            for (var entry : appender.list)
                assertFalse(entry.getFormattedMessage().contains("synthetic-private-marker"));
            verifyNoInteractions(callbacks);
        } finally {
            logger.detachAppender(appender);
            sdkLogger.detachAppender(appender);
            sdkLogger.setLevel(old);
        }
    }

    @Test
    void alipayUsesActualSdkSignatureAndMerchantBinding() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        var config = new AliPayConfigStorage();
        config.setAppid("synthetic-app");
        config.setSeller("synthetic-seller");
        config.setKeyPrivate(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        config.setKeyPublic(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        config.setSignType("RSA2");
        config.setInputCharset("UTF-8");
        config.setTest(false);
        var service = new AliPayService(config);
        var map = new TreeMap<String, Object>();
        map.put("app_id", "synthetic-app");
        map.put("seller_id", "synthetic-seller");
        map.put("trade_status", "TRADE_SUCCESS");
        map.put("out_trade_no", "synthetic-order");
        map.put("trade_no", "synthetic-ali-transaction");
        map.put("total_amount", "0.02");
        map.put("sign", service.createSign(map, "UTF-8"));
        assertTrue(service.verify(map));
        var ali = new AliPayMessageHandler(callbacks);
        assertEquals("success", ali.handleCallback("synthetic", map, service).toMessage());
        clearInvocations(callbacks);
        map.put("seller_id", "other");
        map.remove("sign");
        map.put("sign", service.createSign(map, "UTF-8"));
        assertEquals("fail", ali.handleCallback("synthetic", map, service).toMessage());
        verifyNoInteractions(callbacks);
    }
}
