package co.yixiang.yshop.framework.apilog.core.util;

import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SensitiveDataSanitizerTest {
    @Test
    void couponGuessAndOneTimeCreationResponseAreRedacted() {
        String request=SensitiveDataSanitizer.sanitizeRequest("/app-api/coupon/receive",Map.of(),
                "{\"code\":\"synthetic-coupon-value\",\"requestKey\":\"retry-key\"}",null);
        String response=SensitiveDataSanitizer.sanitizeJson("{\"data\":{\"id\":1,\"exchangeCode\":\"synthetic-coupon-value\",\"redemptionCodeHash\":\"synthetic-digest\"}}",null);
        assertFalse(request.contains("synthetic-coupon-value"));
        assertFalse(response.contains("synthetic-coupon-value"));assertFalse(response.contains("synthetic-digest"));
        assertTrue(response.contains("\"id\":1"));
    }
    @Test
    void stripsPaymentAndIdentitySecretsInMixedCaseNestedArrays() {
        String[] keys = {"keyPrivate", "privateKey", "APIv3Key", "apiKey", "keyCertPwd", "certificatePassword",
                "appSecret", "secret", "sessionKey", "accessToken", "refreshToken", "Authorization",
                "encryptedData", "phoneCode", "password", "OPENID", "Mobile", "API_V3_KEY"};
        for (String key : keys) {
            String input = JsonUtils.toJsonString(Map.of("id", 2, "nested", new Object[]{
                    Map.of(key.toUpperCase(java.util.Locale.ROOT), "synthetic-private-value", "shopId", 2)}));
            String output = SensitiveDataSanitizer.sanitizeJson(input, null);
            assertNotNull(output);
            assertFalse(output.contains("synthetic-private-value"), key);
            assertTrue(output.contains("shopId"));
            assertTrue(output.contains("\"id\":2"));
        }
    }

    @Test
    void preservesBusinessQueryWithoutMutatingInput() {
        Map<String, Object> query = Map.of("id", 12, "shopId", 2, "TOKEN", "synthetic-private-value",
                "Code", "synthetic-private-value", "Password", "synthetic-private-value",
                "nested", Map.of("PrivateKey", "synthetic-private-value"), "customSecret", "synthetic-private-value");
        String output = SensitiveDataSanitizer.sanitizeMap(query, new String[]{"CUSTOMSECRET"});
        assertFalse(output.contains("synthetic-private-value"));
        assertTrue(output.contains("\"id\":12"));
        assertTrue(output.contains("\"shopId\":2"));
        assertEquals("synthetic-private-value", query.get("TOKEN"));
        assertNull(SensitiveDataSanitizer.sanitizeMap(Map.of(), null));
        assertNull(SensitiveDataSanitizer.sanitizeMap(null, null));
    }

    @Test
    void malformedScalarAndFormPayloadsFailClosed() {
        for (String body : new String[]{"{invalid:synthetic-private-value", "keyPrivate=synthetic-private-value",
                "\"synthetic-private-value\"", "null"}) {
            assertNull(SensitiveDataSanitizer.sanitizeJson(body, null));
        }
    }

    @Test
    void malformedPayloadCannotLeakThroughTheGeneralJsonParserLogger() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(JsonUtils.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture =
                new ch.qos.logback.core.read.ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            assertNull(SensitiveDataSanitizer.sanitizeJson("{invalid:synthetic-private-value", null));
            assertNull(SensitiveDataSanitizer.sanitizeJson("{\"id\":2} trailing synthetic-private-value", null));
            assertTrue(capture.list.isEmpty(), "Sanitation must not call a parser that logs input");
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void identityPayloadOmissionRemainsInEffect() {
        assertEquals("{}", SensitiveDataSanitizer.sanitizeRequest("/app-api/member/auth/auth-session",
                Map.of("id", 2), "{\"id\":2}", null));
    }

    @Test
    void stackTraceRetainsCauseLocationsWithoutExceptionMessages() {
        Throwable cause = new IllegalArgumentException("synthetic-private-value");
        Throwable error = new IllegalStateException("synthetic-private-value", cause);
        String result = SensitiveDataSanitizer.safeStackTrace(error);
        assertFalse(result.contains("synthetic-private-value"));
        assertTrue(result.contains("IllegalStateException"));
        assertTrue(result.contains("IllegalArgumentException"));
        assertTrue(result.contains("SensitiveDataSanitizerTest"));
    }
}
