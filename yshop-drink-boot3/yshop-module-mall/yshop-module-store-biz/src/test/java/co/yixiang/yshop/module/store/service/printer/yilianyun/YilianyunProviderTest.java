package co.yixiang.yshop.module.store.service.printer.yilianyun;

import co.yixiang.yshop.module.store.service.printer.PrinterFailure;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class YilianyunProviderTest {
    HttpServer server; ExecutorService executor; YilianyunPrinterProvider provider;
    List<Map<String,String>> requests=new CopyOnWriteArrayList<>();
    volatile String response="{\"error\":0,\"body\":{\"access_token\":\"synthetic-token\",\"state\":1,\"id\":2099743426}}";
    volatile int httpStatus=200; volatile long bodyDelayMillis;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/v2",exchange->{
            String text=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            Map<String,String> form=new HashMap<>();
            for(String pair:text.split("&")) { String[] kv=pair.split("=",2); form.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8)); }
            form.put("path",exchange.getRequestURI().getPath()); form.put("method",exchange.getRequestMethod());
            form.put("contentType",exchange.getRequestHeaders().getFirst("Content-Type")); requests.add(form);
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(httpStatus,bytes.length);
            try(var body=exchange.getResponseBody()) {
                if(bodyDelayMillis>0) { try { Thread.sleep(bodyDelayMillis); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); } }
                body.write(bytes);
            }
        }); server.start();
        YilianyunProperties properties=new YilianyunProperties(); properties.setEnabled(true); properties.setClientId("synthetic-app"); properties.setClientSecret("synthetic-app-secret");
        YilianyunTokenStore tokens=mock(YilianyunTokenStore.class);
        when(tokens.get(anyString(),any())).thenAnswer(call->{ Supplier<String> fetch=call.getArgument(1); return fetch.get(); });
        provider=new YilianyunPrinterProvider(properties,new YilianyunHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v2")),tokens);
    }
    @AfterEach void cleanup() { server.stop(0); executor.shutdownNow(); }
    @Test void officialFormSignatureAuthorizationAndEndpointContract() {
        provider.authorize("4000000001","测试设备",Map.of("terminalKey","synthetic-key"));
        assertEquals(2,requests.size()); Map<String,String> oauth=requests.get(0),device=requests.get(1);
        assertEquals("/v2/oauth/oauth",oauth.get("path")); assertEquals("client_credentials",oauth.get("grant_type")); assertEquals("all",oauth.get("scope"));
        assertEquals("/v2/printer/addprinter",device.get("path")); assertEquals("synthetic-key",device.get("msign"));
        assertEquals("4000000001",device.get("machine_code")); assertEquals("synthetic-token",device.get("access_token"));
        assertEquals("POST",device.get("method")); assertTrue(device.get("contentType").startsWith("application/x-www-form-urlencoded"));
        for(var form:requests) {
            assertEquals(cn.hutool.crypto.digest.DigestUtil.md5Hex("synthetic-app"+form.get("timestamp")+"synthetic-app-secret"),form.get("sign"));
            assertEquals(4,UUID.fromString(form.get("id")).version()); assertFalse(form.containsKey("client_secret"));
        }
        provider.deauthorize("4000000001"); assertEquals("/v2/printer/deleteprinter",requests.get(3).get("path"));
    }
    @Test void normalizedStatusCoversOnlineOfflinePaperOutAndUnknown() {
        DeviceState[] expected={DeviceState.OFFLINE,DeviceState.ONLINE,DeviceState.PAPER_OUT,DeviceState.UNKNOWN};
        int[] codes={0,1,2,99};
        for(int i=0;i<codes.length;i++) {
            response="{\"error\":0,\"body\":{\"access_token\":\"synthetic-token\",\"state\":"+codes[i]+"}}";
            assertEquals(expected[i],provider.status("4000000001"));
            assertEquals("/v2/printer/getprintstatus",requests.get(requests.size()-1).get("path"));
        }
    }
    @Test void receiptCommandsAndIdempotencyAreContainedInAdapter() {
        Receipt receipt=new Receipt("中文测试",List.of("门店<MN>9</MN> **9 @@2","ABC 123 ￥1.00"),1);
        PrintResult result=provider.print("4000000001","stable-origin-1",receipt);
        var form=requests.get(1); assertEquals("/v2/print/index",form.get("path"));
        assertEquals("stable-origin-1",form.get("origin_id")); assertEquals("1",form.get("idempotence"));
        assertTrue(form.get("content").startsWith("<FB>中文测试</FB>")); assertFalse(form.get("content").contains("<MN>"));
        assertFalse(form.get("content").contains("**9")); assertFalse(form.get("content").contains("@@2"));
        assertEquals("2099743426",result.providerJobId());
        assertThrows(PrinterFailure.class,()->YilianyunPrinterProvider.render(new Receipt("test",List.of(),2)));
    }
    @Test void timeoutCoversStalledResponseBodyAndDoesNotRetry() {
        YilianyunHttpTransport transport=new YilianyunHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v2"),java.time.Duration.ofMillis(300));
        bodyDelayMillis=1200;
        PrinterFailure failure=assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                ()->assertThrows(PrinterFailure.class,()->transport.post("/print/index",Map.of("x","y"))));
        assertEquals("PROVIDER_TIMEOUT",failure.getMessage()); assertTrue(failure.uncertain()); assertEquals(1,requests.size());
    }
    @Test void providerErrorsNeverEchoRawSecrets() {
        response="{\"error\":42,\"error_description\":\"synthetic-private-provider-response\"}";
        PrinterFailure failure=assertThrows(PrinterFailure.class,()->provider.status("4000000001"));
        assertEquals("PROVIDER_REJECTED_42",failure.getMessage()); assertFalse(failure.uncertain()); assertNull(failure.getCause());
    }
    @Test void missingConfigurationAndSecretMakeNoNetworkRequests() {
        YilianyunProperties properties=new YilianyunProperties();
        YilianyunPrinterProvider disabled=new YilianyunPrinterProvider(properties,mock(YilianyunTransport.class),mock(YilianyunTokenStore.class));
        assertFalse(disabled.info().configured()); assertThrows(PrinterFailure.class,()->disabled.status("4000000001"));
        assertThrows(PrinterFailure.class,()->provider.authorize("4000000001","test",Map.of())); assertTrue(requests.isEmpty());
        assertThrows(PrinterFailure.class,()->provider.validate("bad-code","USB",Map.of()));
    }
    @Test void malformedMutationResponsesAreUncertainAndResponsesAreBounded() {
        YilianyunHttpTransport transport=new YilianyunHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v2"));
        response="not-json"; assertTrue(assertThrows(PrinterFailure.class,()->transport.post("/print/index",Map.of("x","y"))).uncertain());
        response="x".repeat(70000); assertThrows(PrinterFailure.class,()->transport.post("/print/index",Map.of("x","y")));
        response="{}"; assertThrows(PrinterFailure.class,()->transport.post("/printer/getprintstatus",Map.of("x","y")));
        httpStatus=503; assertTrue(assertThrows(PrinterFailure.class,()->transport.post("/printer/deleteprinter",Map.of("x","y"))).uncertain());
    }
}
