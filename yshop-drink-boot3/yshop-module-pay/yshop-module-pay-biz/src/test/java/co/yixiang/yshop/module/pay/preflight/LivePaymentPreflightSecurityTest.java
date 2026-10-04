package co.yixiang.yshop.module.pay.preflight;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.module.pay.controller.admin.preflight.LivePaymentPreflightController;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class LivePaymentPreflightSecurityTest {
    public static class Permission {
        boolean allowed;

        public boolean hasPermission(String p) {
            return allowed && p.equals("pay:merchant-details:query");
        }
    }

    @Configuration
    @EnableMethodSecurity(jsr250Enabled = true)
    static class Config {
        @Bean(name = "ss")
        Permission permission() {
            return new Permission();
        }

        @Bean
        LivePaymentPreflightService service() {
            return mock(LivePaymentPreflightService.class);
        }

        @Bean
        LivePaymentAuditService audit() {
            return mock(LivePaymentAuditService.class);
        }

        @Bean
        LivePaymentPreflightController controller(
                LivePaymentPreflightService s, LivePaymentAuditService a) {
            return new LivePaymentPreflightController(s, a);
        }
    }

    AnnotationConfigApplicationContext ctx;

    @BeforeEach
    void setup() {
        ctx = new AnnotationConfigApplicationContext(Config.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        ctx.close();
    }

    @Test
    void anonymousCannotReadEitherEndpoint() {
        var c = ctx.getBean(LivePaymentPreflightController.class);
        assertThrows(AccessDeniedException.class, () -> c.check("synthetic"));
        assertThrows(AccessDeniedException.class, c::audit);
        verifyNoInteractions(
                ctx.getBean(LivePaymentPreflightService.class),
                ctx.getBean(LivePaymentAuditService.class));
    }

    @Test
    void authenticatedWithoutPermissionCannotRead() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new TestingAuthenticationToken("synthetic-admin", "unused", "ROLE_USER"));
        var c = ctx.getBean(LivePaymentPreflightController.class);
        assertThrows(AccessDeniedException.class, () -> c.check("synthetic"));
        assertThrows(AccessDeniedException.class, c::audit);
        verifyNoInteractions(
                ctx.getBean(LivePaymentPreflightService.class),
                ctx.getBean(LivePaymentAuditService.class));
    }

    @Test
    void existingMerchantQueryPermissionAllowsReadOnlyDiagnostics() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new TestingAuthenticationToken("synthetic-admin", "unused", "ROLE_USER"));
        ctx.getBean(Permission.class).allowed = true;
        var c = ctx.getBean(LivePaymentPreflightController.class);
        c.check("synthetic");
        c.audit();
        verify(ctx.getBean(LivePaymentPreflightService.class)).check("synthetic");
        verify(ctx.getBean(LivePaymentAuditService.class)).snapshot();
    }
}
