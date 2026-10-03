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
}
