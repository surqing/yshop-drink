package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.framework.apilog.config.YshopApiLogAutoConfiguration;
import co.yixiang.yshop.framework.security.config.*;
import co.yixiang.yshop.framework.web.config.YshopWebAutoConfiguration;
import co.yixiang.yshop.module.infra.api.logger.*;
import co.yixiang.yshop.module.order.controller.app.order.WechatV3CallbackController;
import co.yixiang.yshop.module.order.service.payment.v3.WechatV3PaymentService;
import co.yixiang.yshop.module.pay.controller.admin.preflight.LivePaymentPreflightController;
import co.yixiang.yshop.module.pay.preflight.*;
import co.yixiang.yshop.module.system.api.oauth2.OAuth2TokenApi;
import co.yixiang.yshop.module.system.api.permission.PermissionApi;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;

import javax.net.ssl.*;

/** Actual HTTPS -> Nginx -> Tomcat -> production security/MVC -> official SDK -> database. */
@EnabledIfEnvironmentVariable(named = "YSHOP_INGRESS_BASE_URL", matches = "https://localhost:4844[34]")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CallbackIngressEndToEndTest {
    static WechatV3DatabaseTest fixture;
    static LiveMerchantPreflightDatabaseTest diagnosticsFixture;
    static LivePaymentPreflightService realPreflight;
    ConfigurableApplicationContext web;
    HttpClient http;
    String body;
    Map<String, String> headers;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        ServletWebServerFactoryAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        YshopWebAutoConfiguration.class,
        YshopSecurityAutoConfiguration.class,
        YshopWebSecurityConfigurerAdapter.class,
        YshopApiLogAutoConfiguration.class
    })
    static class Web {
        @Bean
        WechatV3PaymentService payment() {
            return fixture.service;
        }

        @Bean
        WechatV3CallbackController callback(WechatV3PaymentService p) {
            return new WechatV3CallbackController(p);
        }

        @Bean
        LivePaymentAuditService audit() {
            return new LivePaymentAuditService(fixture.f.jdbc);
        }

        @Bean
        LivePaymentPreflightService preflight() {
            return realPreflight;
        }

        @Bean
        LivePaymentPreflightController diagnostics(
                LivePaymentPreflightService p, LivePaymentAuditService a) {
            return new LivePaymentPreflightController(p, a);
        }

        @Bean
        OAuth2TokenApi tokens() {
            var token = mock(OAuth2TokenApi.class);
            when(token.checkAccessToken("synthetic-admin-token"))
                    .thenReturn(
                            new co.yixiang.yshop.module.system.api.oauth2.dto
                                            .OAuth2AccessTokenCheckRespDTO()
                                    .setUserId(1L)
                                    .setUserType(2)
                                    .setTenantId(1L)
                                    .setScopes(List.of()));
            return token;
        }

        @Bean
        PermissionApi permissions() {
            return mock(
                    PermissionApi.class,
                    invocation ->
                            invocation.getMethod().getReturnType() == Boolean.class
                                            || invocation.getMethod().getReturnType()
                                                    == boolean.class
                                    ? true
                                    : null);
        }

        @Bean
        co.yixiang.yshop.module.pay.credential.PaymentCredentialCryptoService crypto() {
            return (co.yixiang.yshop.module.pay.credential.PaymentCredentialCryptoService)
                    org.springframework.test.util.ReflectionTestUtils.getField(
                            diagnosticsFixture.preflight, "crypto");
        }

        @Bean
        co.yixiang.yshop.module.pay.dal.mysql.merchantdetails.MerchantDetailsMapper merchants() {
            return fixture.f
                    .ctx
                    .getBean(org.apache.ibatis.session.SqlSessionFactory.class)
                    .getConfiguration()
                    .getMapper(
                            co.yixiang.yshop.module.pay.dal.mysql.merchantdetails
                                    .MerchantDetailsMapper.class,
                            new org.mybatis.spring.SqlSessionTemplate(
                                    fixture.f.ctx.getBean(
                                            org.apache.ibatis.session.SqlSessionFactory.class)));
        }

        @Bean
        co.yixiang.yshop.module.pay.service.merchantdetails.MerchantDetailsServiceImpl
                merchantService() {
            return new co.yixiang.yshop.module.pay.service.merchantdetails
                    .MerchantDetailsServiceImpl();
        }

        @Bean
        co.yixiang.yshop.module.pay.controller.admin.merchantdetails.MerchantDetailsController
                merchantController() {
            return new co.yixiang.yshop.module.pay.controller.admin.merchantdetails
                    .MerchantDetailsController();
        }

        @Bean
        ApiErrorLogApi errors() {
            return mock(ApiErrorLogApi.class);
        }

        @Bean
        ApiAccessLogApi logs() {
            return mock(ApiAccessLogApi.class);
        }

        @Bean
        AuthorizeRequestsCustomizer noAdditionalPublicRoutes() {
            return new AuthorizeRequestsCustomizer() {
                @Override
                public void customize(
                        AuthorizeHttpRequestsConfigurer<HttpSecurity>
                                        .AuthorizationManagerRequestMatcherRegistry
                                r) {}
            };
        }
    }

    @BeforeAll
    void start() throws Exception {
        diagnosticsFixture = new LiveMerchantPreflightDatabaseTest();
        diagnosticsFixture.setup();
        fixture = diagnosticsFixture.v;
        realPreflight = spy(diagnosticsFixture.preflight);
        var a = fixture.attempt();
        var input =
                fixture.notification(
                        fixture.transaction(a.getProviderOrderReference(), "synthetic-ingress-T1"),
                        false,
                        false);
        body = input.getBody();
        String[] message = input.getMessage().split("\n", 3);
        headers =
                new LinkedHashMap<>(
                        Map.of(
                                "Wechatpay-Serial",
                                input.getSerialNumber(),
                                "Wechatpay-Timestamp",
                                message[0],
                                "Wechatpay-Nonce",
                                message[1],
                                "Wechatpay-Signature",
                                input.getSignature()));
        var trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (var in = Files.newInputStream(Path.of(System.getenv("YSHOP_INGRESS_CA")))) {
            trust.setCertificateEntry(
                    "synthetic-local-ca",
                    CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tm.getTrustManagers(), null);
        http =
                HttpClient.newBuilder()
                        .sslContext(ssl)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        var app = new SpringApplication(Web.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setDefaultProperties(
                Map.of(
                        "server.port",
                        "48881",
                        "server.address",
                        "127.0.0.1",
                        "spring.application.name",
                        "synthetic-ingress",
                        "yshop.web.admin-ui.url",
                        "http://localhost",
                        "spring.main.banner-mode",
                        "off",
                        "logging.level.root",
                        "WARN"));
        web = app.run();
    }

    HttpResponse<String> post(String id, String raw, Map<String, String> h) throws Exception {
        var b =
                HttpRequest.newBuilder(
                                URI.create(
                                        System.getenv("YSHOP_INGRESS_BASE_URL") + "/app-api/order/notify/wechat-v3/"
                                                + id))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(raw));
        h.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void validSyntheticAnonymousNotification() throws Exception {
        assertEquals(204, post("merchant-wx", body, headers).statusCode());
        fixture.f.effects(1);
    }

    @Test
    @Order(2)
    void replayExactlyOnce() throws Exception {
        assertEquals(204, post("merchant-wx", body, headers).statusCode());
        fixture.f.effects(1);
        if (PaymentDatabaseTest.mysqlAcceptance()) verifyReadOnlyHarness();
    }

    void verifyReadOnlyHarness() throws Exception {
        Path root = Path.of("../../../").toAbsolutePath().normalize();
        Path evidence =
                Path.of(System.getenv().getOrDefault("YSHOP_TEST_WORKSPACE", root.getParent().toString()))
                        .resolve(".local-dev/private/synthetic-evidence-" + UUID.randomUUID());
        Files.createDirectories(evidence);
        Files.setPosixFilePermissions(
                evidence, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        try {
            String id =
                    fixture.f.jdbc.queryForObject(
                            "SELECT attempt_id FROM yshop_order_payment_attempt WHERE"
                                + " order_id='order-A'",
                            String.class);
            var binding =
                    new LinkedHashMap<String, Object>(
                            Map.of(
                                    "orderId",
                                    "order-A",
                                    "attemptId",
                                    id,
                                    "providerOrderReference",
                                    id,
                                    "transactionId",
                                    "synthetic-ingress-T1",
                                    "appid",
                                    "synthetic-app",
                                    "mchId",
                                    "synthetic-merchant",
                                    "amountCents",
                                    2,
                                    "currency",
                                    "CNY",
                                    "provider",
                                    "WECHAT"));
            binding.put("tradeState", "SUCCESS");
            binding.put("observedDeliveryCount", 2);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            Files.writeString(
                    evidence.resolve("client.json"),
                    json.writeValueAsString(
                            Map.of(
                                    "orderId",
                                    "order-A",
                                    "attemptId",
                                    id,
                                    "paymentResult",
                                    "requestPayment:ok")));
            Files.writeString(evidence.resolve("callback.json"), json.writeValueAsString(binding));
            Files.writeString(evidence.resolve("query.json"), json.writeValueAsString(binding));
            try (var files = Files.list(evidence)) {
                for (Path p : files.toList())
                    Files.setPosixFilePermissions(
                            p,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
            var helper =
                    new ProcessBuilder(
                                    "python3",
                                    root.resolve("scripts/payment/acceptance-harness.py")
                                            .toString(),
                                    "--evidence-dir",
                                    evidence.toString())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(
                            helper.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, helper.waitFor(), output);
            assertTrue(output.contains("\"consistentObservedEvidence\": true"));
            assertTrue(output.contains("\"providerCalls\": 0"));
            assertTrue(output.contains("\"financialWrites\": 0"));
        } finally {
            try (var files = Files.walk(evidence)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    @Test
    @Order(3)
    void whitespaceTamper() throws Exception {
        assertEquals(400, post("merchant-wx", body + " ", headers).statusCode());
    }

    @Test
    @Order(4)
    void missingSerial() throws Exception {
        var h = new LinkedHashMap<>(headers);
        h.remove("Wechatpay-Serial");
        assertEquals(400, post("merchant-wx", body, h).statusCode());
    }

    @Test
    @Order(5)
    void missingSignature() throws Exception {
        var h = new LinkedHashMap<>(headers);
        h.remove("Wechatpay-Signature");
        assertEquals(400, post("merchant-wx", body, h).statusCode());
    }

    @Test
    @Order(6)
    void expiredTimestamp() throws Exception {
        var h = new LinkedHashMap<>(headers);
        h.put("Wechatpay-Timestamp", "1");
        assertEquals(400, post("merchant-wx", body, h).statusCode());
    }

    @Test
    @Order(7)
    void wrongDetailsId() throws Exception {
        assertEquals(400, post("wrong-merchant", body, headers).statusCode());
    }

    @Test
    @Order(8)
    void proxyRejectsOversize() throws Exception {
        assertEquals(413, post("merchant-wx", " ".repeat(65537), headers).statusCode());
    }

    @Test
    @Order(9)
    void adminRemainsProtected() throws Exception {
        for (String suffix :
                List.of(
                        "audit",
                        "check?detailsId=merchant-wx",
                        "deployment?detailsId=merchant-wx")) {
            var r =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "https://localhost:48443/admin-api/pay/live-preflight/"
                                                            + suffix))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertTrue(
                    r.statusCode() == 401
                            || r.statusCode() == 403
                            || r.body().matches(".*\\\"code\\\":(?:401|403).*"));
        }
        verifyNoInteractions(web.getBean(OAuth2TokenApi.class));
        verifyNoInteractions(web.getBean(LivePaymentPreflightService.class));
    }

    @Test
    @Order(10)
    void helperUsesAuthenticatedHttpsAndServerEncryption() throws Exception {
        Assumptions.assumeTrue(
                PaymentDatabaseTest.mysqlAcceptance(),
                "Full real schema required for helper offline configurationReady proof");
        Path root = Path.of("../../../").toAbsolutePath().normalize();
        Path staging =
                Path.of(System.getenv().getOrDefault("YSHOP_TEST_WORKSPACE", root.getParent().toString()))
                        .resolve(".local-dev/private/synthetic-provision-" + UUID.randomUUID());
        Files.createDirectories(staging);
        var directoryPermissions =
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
        var filePermissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(staging, directoryPermissions);
        try {
            Files.writeString(
                    staging.resolve("merchant-private.pem"),
                    LiveMerchantPreflightDatabaseTest.pem(
                            WechatV3DatabaseTest.MERCHANT.getPrivate(), "PRIVATE KEY"));
            Files.writeString(
                    staging.resolve("platform-public.pem"),
                    LiveMerchantPreflightDatabaseTest.pem(
                            WechatV3DatabaseTest.PLATFORM.getPublic(), "PUBLIC KEY"));
            Files.writeString(
                    staging.resolve("api-v3-key.txt"),
                    UUID.randomUUID().toString().replace("-", ""));
            Files.writeString(staging.resolve("token.txt"), "synthetic-admin-token");
            String id = "synthetic-provision";
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            Files.writeString(
                    staging.resolve("metadata.json"),
                    json.writeValueAsString(
                            Map.of(
                                    "detailsId",
                                    id,
                                    "appid",
                                    "wx0000000000000000",
                                    "mchId",
                                    "0000000000",
                                    "merchantCertificateSerial",
                                    "ABC123",
                                    "platformPublicKeyId",
                                    "PUB_KEY_ID_SYNTHETIC",
                                    "notifyUrl",
                                    "https://synthetic.invalid/app-api/order/notify/wechat-v3/"
                                            + id)));
            Files.writeString(
                    staging.resolve("connection.json"),
                    json.writeValueAsString(
                            Map.of(
                                    "adminOrigin",
                                    System.getenv("YSHOP_INGRESS_BASE_URL"),
                                    "caFile",
                                    System.getenv("YSHOP_INGRESS_CA"),
                                    "tokenFile",
                                    staging.resolve("token.txt").toString())));
            for (Path p : Files.list(staging).toList())
                Files.setPosixFilePermissions(p, filePermissions);
            var cert =
                    new ProcessBuilder(
                                    "openssl",
                                    "req",
                                    "-new",
                                    "-x509",
                                    "-key",
                                    staging.resolve("merchant-private.pem").toString(),
                                    "-subj",
                                    "/CN=synthetic-test-merchant",
                                    "-set_serial",
                                    "0xABC123",
                                    "-days",
                                    "1",
                                    "-out",
                                    staging.resolve("merchant-cert.pem").toString())
                            .redirectErrorStream(true)
                            .start();
            cert.getInputStream().readAllBytes();
            assertEquals(0, cert.waitFor());
            Files.setPosixFilePermissions(staging.resolve("merchant-cert.pem"), filePermissions);
            var helper =
                    new ProcessBuilder(
                                    "python3",
                                    root.resolve("scripts/payment/provision-merchant.py")
                                            .toString(),
                                    "--private-dir",
                                    staging.toString(),
                                    "--apply",
                                    "--connection-file",
                                    staging.resolve("connection.json").toString())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(
                            helper.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, helper.waitFor(), output);
            assertTrue(output.contains("SERVER_OFFLINE_PREFLIGHT_PASS"));
            assertFalse(output.contains("BEGIN PRIVATE KEY"));
            assertFalse(output.contains("enc:v1:"));
            assertFalse(output.contains("synthetic-admin-token"));
            assertTrue(
                    fixture.f
                            .jdbc
                            .queryForObject(
                                    "SELECT key_private FROM merchant_details WHERE details_id=?",
                                    String.class,
                                    id)
                            .startsWith("enc:v1:"));
            assertTrue(
                    fixture.f
                            .jdbc
                            .queryForObject(
                                    "SELECT api_v3_key FROM merchant_details WHERE details_id=?",
                                    String.class,
                                    id)
                            .startsWith("enc:v1:"));
            assertFalse(realPreflight.check(id).liveReady());
            assertTrue(realPreflight.check(id).configurationReady());
            assertEquals(0, fixture.calls.get());
            // Explicit restoration is confined to an empty, sanitized, deleted placeholder.
            fixture.f.jdbc.update(
                    "INSERT INTO merchant_details(details_id,pay_type,appid,mch_id,deleted)"
                        + " VALUES('wx_miniapp','wxPay','local-unconfigured-synthetic','',1)");
            Files.writeString(
                    staging.resolve("metadata.json"),
                    json.writeValueAsString(
                            Map.of(
                                    "detailsId",
                                    "wx_miniapp",
                                    "appid",
                                    "wx0000000000000000",
                                    "mchId",
                                    "0000000000",
                                    "merchantCertificateSerial",
                                    "ABC123",
                                    "platformPublicKeyId",
                                    "PUB_KEY_ID_SYNTHETIC",
                                    "notifyUrl",
                                    "https://synthetic.invalid/app-api/order/notify/wechat-v3/wx_miniapp")));
            for (int repeat = 0; repeat < 2; repeat++) {
                var restore =
                        new ProcessBuilder(
                                        "python3",
                                        root.resolve("scripts/payment/provision-merchant.py")
                                                .toString(),
                                        "--private-dir",
                                        staging.toString(),
                                        "--apply",
                                        "--restore-empty-placeholder",
                                        "--connection-file",
                                        staging.resolve("connection.json").toString())
                                .redirectErrorStream(true)
                                .start();
                String safeOutput =
                        new String(
                                restore.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(repeat == 0 ? 0 : 1, restore.waitFor(), safeOutput);
            }
            assertTrue(realPreflight.check("wx_miniapp").configurationReady());
            assertFalse(realPreflight.check("wx_miniapp").liveReady());
        } finally {
            try (var files = Files.walk(staging)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    @AfterAll
    void close() {
        if (web != null) {
            for (var invocation :
                    mockingDetails(web.getBean(ApiAccessLogApi.class)).getInvocations()) {
                for (Object arg : invocation.getArguments()) {
                    String logged = String.valueOf(arg);
                    assertFalse(logged.contains(body));
                    assertFalse(logged.contains(headers.get("Wechatpay-Signature")));
                    assertFalse(logged.contains("enc:v1:"));
                    assertFalse(logged.contains("synthetic-admin-token"));
                }
            }
            web.close();
        }
        if (fixture != null) {
            assertEquals(0, fixture.calls.get());
            fixture.f.effects(1);
            fixture.close();
        }
    }
}
