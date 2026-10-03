package co.yixiang.yshop.framework.web.core.handler;

import co.yixiang.yshop.framework.apilog.core.service.ApiErrorLogFrameworkService;
import co.yixiang.yshop.framework.web.core.util.WebFrameworkUtils;
import co.yixiang.yshop.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalExceptionHandlerTest {
    @Test
    void paymentExceptionAuditSanitizesBodyQueryAndCauseMessages() {
        ApiErrorLogFrameworkService service = mock(ApiErrorLogFrameworkService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test", service);
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/admin-api/pay/merchant-details/update");
        WebFrameworkUtils.setLoginUserType(req, 2);
        req.setContentType("application/json");
        req.setContent("{\"id\":2,\"nested\":[{\"privateKEY\":\"synthetic-private-value\",\"keyCertPwd\":\"synthetic-private-value\",\"APIv3Key\":\"synthetic-private-value\"}]}".getBytes(StandardCharsets.UTF_8));
        req.addParameter("id", "2");
        req.addParameter("Authorization", "synthetic-private-value");
        handler.defaultExceptionHandler(req, new IllegalStateException("synthetic-private-value",
                new IllegalArgumentException("synthetic-private-value")));
        ArgumentCaptor<ApiErrorLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(service).createApiErrorLog(captor.capture());
        ApiErrorLogCreateReqDTO dto = captor.getValue();
        for (String value : new String[]{dto.getRequestParams(), dto.getExceptionMessage(),
                dto.getExceptionRootCauseMessage(), dto.getExceptionStackTrace()}) {
            assertNotNull(value);
            assertFalse(value.contains("synthetic-private-value"));
        }
        assertTrue(dto.getRequestParams().contains("id"));
        assertTrue(dto.getExceptionStackTrace().contains("IllegalArgumentException"));
        assertEquals(req.getRequestURI(), dto.getRequestUrl());
    }
}
