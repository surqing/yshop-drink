package co.yixiang.yshop.module.store.printer;

import co.yixiang.yshop.framework.apilog.core.util.SensitiveDataSanitizer;
import co.yixiang.yshop.module.store.controller.admin.printer.PrinterController;
import co.yixiang.yshop.module.store.controller.admin.printer.OrderPrintJobController;
import co.yixiang.yshop.module.store.service.printer.OrderPrintJobService;
import co.yixiang.yshop.module.store.service.printer.PrinterService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.*;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrinterControllerTest {
    @Configuration @EnableMethodSecurity(proxyTargetClass=true)
    static class Config {
        @Bean PrinterService service() { return mock(PrinterService.class); }
        @Bean PrinterController controller(PrinterService service) { return new PrinterController(service); }
        @Bean OrderPrintJobService orderPrintJobService() { return mock(OrderPrintJobService.class); }
        @Bean OrderPrintJobController orderPrintJobController(OrderPrintJobService service) { return new OrderPrintJobController(service); }
        @Bean(name="ss") Permissions permissions() { return new Permissions(); }
    }
    public static class Permissions {
        Set<String> allowed=Set.of();
        public boolean hasPermission(String permission) { return allowed.contains(permission); }
    }
    record Action(String permission,Runnable call) {}
    @Test void everyEndpointRequiresItsOwnPermissionBeforeCallingService() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            PrinterController controller=context.getBean(PrinterController.class);
            PrinterService service=context.getBean(PrinterService.class);
            OrderPrintJobController orderJobs=context.getBean(OrderPrintJobController.class);
            OrderPrintJobService orderJobService=context.getBean(OrderPrintJobService.class);
            Permissions permissions=context.getBean(Permissions.class);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test",null,List.of()));
            List<Action> actions=List.of(
                new Action("query",controller::providers),new Action("query",controller::shops),
                new Action("query",()->controller.page(1,10,null,null,false)),new Action("query",()->controller.get(1)),
                new Action("query",()->controller.status(1)),new Action("query",()->controller.refreshStatus(1)),new Action("query",()->controller.getJob(1,"synthetic-request-01")),new Action("query",()->controller.jobs(1,1,10)),
                new Action("create",()->controller.create(new SaveRequest(null,"测试","YILIAN_YUN","40000001","","CLOUD",true,Map.of()))),
                new Action("update",()->controller.update(new SaveRequest(1L,"测试","YILIAN_YUN","40000001","","CLOUD",true,Map.of()))),
                new Action("delete",()->controller.delete(1)),new Action("authorize",()->controller.authorize(1)),
                new Action("authorize",()->controller.deauthorize(1)),new Action("authorize",()->controller.reconnect(1)),new Action("binding",()->controller.bind(new BindingRequest(1L,1L))),
                new Action("binding",()->controller.unbind(1)),new Action("test",()->controller.test(new TestRequest(1L,"synthetic-request-01"))));
            actions = new ArrayList<>(actions);
            actions.addAll(List.of(new Action("query",()->orderJobs.page(1,20,null)),
                    new Action("query",()->orderJobs.get(1L)),new Action("query",()->orderJobs.refresh(1L)),
                    new Action("test",()->orderJobs.reprint(new OrderPrintJobService.ReprintRequest(1L,"manual-reprint-0001","synthetic reason")))));
            for(Action action:actions) {
                permissions.allowed=Set.of(); clearInvocations(service,orderJobService);
                assertThrows(AccessDeniedException.class,action.call()::run); verifyNoInteractions(service,orderJobService);
                permissions.allowed=Set.of("store:web-print:"+action.permission());
                assertDoesNotThrow(action.call()::run);
                assertTrue(!mockingDetails(service).getInvocations().isEmpty()
                        || !mockingDetails(orderJobService).getInvocations().isEmpty());
            }
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void invalidBodiesCannotReachService() throws Exception {
        PrinterService service=mock(PrinterService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new PrinterController(service)).build();
        mvc.perform(post("/store/printer/create").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"providerCode\":\"YILIAN_YUN\",\"deviceCode\":\"40000001\",\"connectionType\":\"CLOUD\",\"enabled\":true,\"configuration\":{}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/store/printer/test-print").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"requestId\":\"short\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        OrderPrintJobService orderJobs=mock(OrderPrintJobService.class);
        var orderMvc=MockMvcBuilders.standaloneSetup(new OrderPrintJobController(orderJobs)).build();
        orderMvc.perform(post("/store/order-print-job/reprint").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":-1,\"requestKey\":\"short\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderJobs);
    }
    @Test void statusGetIsCachedAndRefreshUsesExplicitPost() throws Exception {
        PrinterService service=mock(PrinterService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new PrinterController(service)).build();
        mvc.perform(get("/store/printer/status").param("id","1")).andExpect(status().isOk());
        verify(service,never()).status(anyLong());
        mvc.perform(post("/store/printer/status/refresh").param("id","1")).andExpect(status().isOk());
        verify(service).status(1L);
        mvc.perform(get("/store/printer/status/refresh").param("id","1")).andExpect(status().isMethodNotAllowed());
    }
    @Test void auditAndDtoNeverEchoPrinterCredentials() {
        String sanitized=SensitiveDataSanitizer.sanitizeJson("{\"name\":\"测试\",\"configuration\":{\"terminalKey\":\"synthetic-key\"},\"encrypted_config\":\"synthetic-ciphertext\",\"YSHOP_PRINTER_ENCRYPTION_KEY\":\"synthetic-master-key\"}",null);
        assertTrue(sanitized.contains("测试")); assertFalse(sanitized.contains("synthetic"));
        assertFalse(new SaveRequest(null,"测试","YILIAN_YUN","40000001","","CLOUD",true,Map.of("terminalKey","synthetic-key")).toString().contains("synthetic-key"));
    }
}
