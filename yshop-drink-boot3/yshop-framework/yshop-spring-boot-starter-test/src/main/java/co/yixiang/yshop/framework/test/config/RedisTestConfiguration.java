package co.yixiang.yshop.framework.test.config;

import com.github.fppt.jedismock.RedisServer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedHashSet;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;

/**
 * Redis 测试 Configuration，主要实现内嵌 Redis 的启动
 *
 * @author yshop
 */
@Configuration(proxyBeanMethods = false)
@Lazy(false) // 禁止延迟加载
@EnableConfigurationProperties(RedisProperties.class)
public class RedisTestConfiguration {

    /**
     * 创建模拟的 Redis Server 服务器
     */
    @Bean(destroyMethod = "stop")
    public RedisServer redisServer(RedisProperties properties) throws IOException {
        RedisServer redisServer = new RedisServer(0, InetAddress.getByName("127.0.0.1"));
        redisServer.start(); // Fail startup rather than silently using another server.
        properties.setUrl(null);
        properties.setHost("127.0.0.1");
        properties.setPort(redisServer.getBindPort());
        properties.setDatabase(0);
        properties.setUsername(null);
        properties.setPassword(null);
        return redisServer;
    }

    @Bean
    public static BeanFactoryPostProcessor ownedRedisBeforeClients() {
        return factory -> {
            for (String name : new String[]{"redisConnectionDetails", "redisConnectionFactory",
                    "redisson", "redissonConnectionFactory", "stringRedisTemplate", "redisTemplate"}) {
                if (!factory.containsBeanDefinition(name)) continue;
                var definition = factory.getBeanDefinition(name);
                var dependencies = new LinkedHashSet<String>();
                if (definition.getDependsOn() != null)
                    dependencies.addAll(Arrays.asList(definition.getDependsOn()));
                dependencies.add("redisServer");
                definition.setDependsOn(dependencies.toArray(String[]::new));
            }
        };
    }

}
