package co.yixiang.yshop.framework.test.config;

import com.github.fppt.jedismock.RedisServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.*;
import java.net.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class RedisTestConfigurationTest {
    @Test void occupiedConfiguredPortCannotBecomeAnUnstartedSharedServer() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var properties = new RedisProperties(); properties.setPort(occupied.getLocalPort());
            properties.setUrl("redis://external.invalid:6379"); properties.setPassword("synthetic-password");
            RedisServer server = new RedisTestConfiguration().redisServer(properties);
            try {
                assertNotEquals(occupied.getLocalPort(), server.getBindPort());
                assertNull(properties.getUrl()); assertNull(properties.getPassword());
                assertEquals(server.getBindPort(), properties.getPort());
                assertEquals("PONG", request(server, "*1\r\n$4\r\nPING\r\n"));
            } finally { server.stop(); }
        }
    }

    @Test void independentServersHaveIndependentPortsAndKeyspaces() throws Exception {
        RedisServer first = new RedisTestConfiguration().redisServer(new RedisProperties());
        try {
            RedisServer second = new RedisTestConfiguration().redisServer(new RedisProperties());
            try {
                assertNotEquals(first.getBindPort(), second.getBindPort());
                assertEquals("OK", request(first,"*3\r\n$3\r\nSET\r\n$8\r\nqa-owned\r\n$3\r\none\r\n"));
                assertNull(request(second,"*2\r\n$3\r\nGET\r\n$8\r\nqa-owned\r\n"));
                assertEquals("one", request(first,"*2\r\n$3\r\nGET\r\n$8\r\nqa-owned\r\n"));
            } finally { second.stop(); }
        } finally { first.stop(); }
    }

    @Configuration static class EarlyClient {
        @Bean(name="redisConnectionFactory") Integer port(RedisProperties p) { return p.getPort(); }
    }
    @Test void clientInitializationDependsOnOwnedServerAndContextClosesIt() throws Exception {
        int port;
        try (var context = new AnnotationConfigApplicationContext(EarlyClient.class,RedisTestConfiguration.class)) {
            port=context.getBean(RedisServer.class).getBindPort();
            assertEquals(port,context.getBean("redisConnectionFactory"));
        }
        assertThrows(IOException.class,()->{try(var socket=new Socket()){socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),500);}});
    }
    String request(RedisServer server,String input) throws Exception {
        try (var socket=new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),server.getBindPort()),500);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write(input.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String header=reader.readLine();
            if ("$-1".equals(header)) return null;
            if (header.startsWith("$")) {
                String value=reader.readLine();
                assertEquals(Integer.parseInt(header.substring(1)),value.length());
                return value;
            }
            assertTrue(header.startsWith("+"), "Expected a successful RESP reply");
            return header.substring(1);
        }
    }
}
