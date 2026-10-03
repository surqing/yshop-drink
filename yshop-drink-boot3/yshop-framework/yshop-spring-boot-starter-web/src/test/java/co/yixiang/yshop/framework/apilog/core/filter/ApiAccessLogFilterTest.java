package co.yixiang.yshop.framework.apilog.core.filter;

import co.yixiang.yshop.framework.apilog.core.util.ApiLogUtils;
import co.yixiang.yshop.framework.apilog.core.service.ApiAccessLogFrameworkService;
import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.framework.web.config.WebProperties;
import co.yixiang.yshop.framework.web.core.util.WebFrameworkUtils;
import co.yixiang.yshop.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiAccessLogFilterTest {
    @Test
    void paymentCreateAndUpdateAuditsUseSharedSanitizer() throws Exception {
        for (String method : new String[]{"POST", "PUT"}) {
            ApiAccessLogFrameworkService service = mock(ApiAccessLogFrameworkService.class);
            ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test", service);
            String path = "/admin-api/pay/merchant-details/" + (method.equals("POST") ? "create" : "update");
            MockHttpServletRequest req = new MockHttpServletRequest(method, path);
            WebFrameworkUtils.setLoginUserType(req, 2);
            req.setContentType("application/json");
            req.setContent(("{\"id\":2,\"nested\":[{\"KEYPRIVATE\":\"synthetic-private-value\","
                    + "\"keyCertPwd\":\"synthetic-private-value\",\"APIv3Key\":\"synthetic-private-value\"}]}").getBytes(StandardCharsets.UTF_8));
            req.addParameter("shopId", "2");
            req.addParameter("ToKeN", "synthetic-private-value");
            WebFrameworkUtils.setCommonResult(req, CommonResult.error(500, "synthetic-private-value"));
            filter.doFilter(req, new MockHttpServletResponse(), (request, response) -> { });
            ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
            verify(service).createApiAccessLog(captor.capture());
            ApiAccessLogCreateReqDTO dto = captor.getValue();
            assertFalse(dto.getRequestParams().contains("synthetic-private-value"));
            assertFalse(dto.getResultMsg().contains("synthetic-private-value"));
            assertTrue(dto.getRequestParams().contains("shopId"));
            assertTrue(dto.getRequestParams().contains("id"));
            assertEquals(path, dto.getRequestUrl());
            assertEquals(method, dto.getRequestMethod());
            assertEquals(500, dto.getResultCode());
        }
    }

    @Test
    void explicitlyEnabledResponseAuditStillRemovesPaymentSecrets() throws Exception {
        ApiAccessLogFrameworkService service = mock(ApiAccessLogFrameworkService.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test", service);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin-api/pay/merchant-details/get");
        WebFrameworkUtils.setLoginUserType(req, 2);
        req.setAttribute("HANDLER_METHOD", new org.springframework.web.method.HandlerMethod(
                new ResponseAuditController(), "get"));
        WebFrameworkUtils.setCommonResult(req, CommonResult.success(java.util.Map.of(
                "id", 2, "keyPrivate", "synthetic-private-value", "keyCertPwd", "synthetic-private-value")));
        filter.doFilter(req, new MockHttpServletResponse(), (request, response) -> { });
        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(service).createApiAccessLog(captor.capture());
        String body = captor.getValue().getResponseBody();
        assertNotNull(body);
        assertFalse(body.contains("synthetic-private-value"));
        assertTrue(body.contains("\"id\":2"));
    }

    static class ResponseAuditController {
        @co.yixiang.yshop.framework.apilog.core.annotation.ApiAccessLog(responseEnable = true)
        public void get() { }
    }

    @Test
    void recognizesIdentityEndpointsWithoutDroppingOrderAudit() {
        assertTrue(ApiLogUtils.isIdentityRequest("/app-api/member/auth/auth-session"));
        assertTrue(ApiLogUtils.isIdentityRequest("/admin-api/system/auth/login"));
        assertTrue(ApiLogUtils.isIdentityRequest("/app-api/member/user/get-info"));
        assertFalse(ApiLogUtils.isIdentityRequest("/app-api/order/create"));
    }
    @Test
    void callbackNeverAuditsRawBodyQueryOrSignature() throws Exception {
        ApiAccessLogFrameworkService service = mock(ApiAccessLogFrameworkService.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test", service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app-api/order/notify/payBacksynthetic.json");
        WebFrameworkUtils.setLoginUserType(request, 1);
        request.setContentType("application/json");
        request.setContent("{\"openid\":\"synthetic-private-marker\",\"sign\":\"synthetic-private-marker\"}".getBytes(StandardCharsets.UTF_8));
        request.addParameter("sign", "synthetic-private-marker");
        filter.doFilter(request, new MockHttpServletResponse(), (req, resp) -> { });
        var captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(service).createApiAccessLog(captor.capture());
        assertNull(captor.getValue().getRequestParams());
        assertNull(captor.getValue().getResponseBody());
    }

    @Test
    void localAccessLogSwitchControlsRegistration() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(co.yixiang.yshop.framework.apilog.config.YshopApiLogAutoConfiguration.class)
                .withBean(WebProperties.class, WebProperties::new)
                .withBean(co.yixiang.yshop.module.infra.api.logger.ApiAccessLogApi.class,
                        () -> mock(co.yixiang.yshop.module.infra.api.logger.ApiAccessLogApi.class))
                .withBean(co.yixiang.yshop.module.infra.api.logger.ApiErrorLogApi.class,
                        () -> mock(co.yixiang.yshop.module.infra.api.logger.ApiErrorLogApi.class))
                .withPropertyValues("spring.application.name=test");
        runner.withPropertyValues("yshop.access-log.enable=false").run(ctx -> assertFalse(ctx.containsBean("apiAccessLogFilter")));
        runner.withPropertyValues("yshop.access-log.enable=true").run(ctx -> assertTrue(ctx.containsBean("apiAccessLogFilter")));
    }

}
