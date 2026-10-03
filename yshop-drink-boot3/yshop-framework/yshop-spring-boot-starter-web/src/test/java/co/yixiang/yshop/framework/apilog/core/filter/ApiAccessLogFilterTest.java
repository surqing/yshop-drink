package co.yixiang.yshop.framework.apilog.core.filter;

import co.yixiang.yshop.framework.apilog.core.util.ApiLogUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ApiAccessLogFilterTest {
    @Test
    void stripsNestedCredentialsWhileRetainingBusinessIds() {
        String input = "{\"id\":2,\"auth\":{\"code\":\"test-private-value\","
                + "\"openid\":\"test-private-value\",\"sessionKey\":\"test-private-value\","
                + "\"mobile\":\"test-private-value\",\"accessToken\":\"test-private-value\"}}";
        String output = ReflectionTestUtils.invokeMethod(ApiAccessLogFilter.class,
                "sanitizeJson", input, new String[0]);
        assertNotNull(output);
        assertFalse(output.contains("test-private-value"));
        assertTrue(output.contains("\"id\":2"));
    }

    @Test
    void malformedJsonFailsClosed() {
        assertNull(ReflectionTestUtils.invokeMethod(ApiAccessLogFilter.class,
                "sanitizeJson", "{invalid:test-private-value", new String[0]));
    }

    @Test
    void recognizesIdentityEndpointsWithoutDroppingOrderAudit() {
        assertTrue(ApiLogUtils.isIdentityRequest("/app-api/member/auth/auth-session"));
        assertTrue(ApiLogUtils.isIdentityRequest("/admin-api/system/auth/login"));
        assertTrue(ApiLogUtils.isIdentityRequest("/app-api/member/user/get-info"));
        assertFalse(ApiLogUtils.isIdentityRequest("/app-api/order/create"));
    }
}
