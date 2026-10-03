package co.yixiang.yshop.module.member.dal.redis.order;

import co.yixiang.yshop.module.member.framework.auth.config.MiniAppAuthProperties;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiniRedisDAOTest {
    @Test
    void cachesSessionWithConfiguredTimeout() {
        MiniAppAuthProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("yshop.member.auth.mini-session-timeout", "5m")))
                .bind("yshop.member.auth", Bindable.of(MiniAppAuthProperties.class)).get();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        MiniRedisDAO dao = new MiniRedisDAO();
        ReflectionTestUtils.setField(dao, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(dao, "authProperties", properties);

        dao.set("test-session", "test-identity");

        verify(values).set("yshop_mini_login_cache:test-identity", "test-session", Duration.ofMinutes(5));
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void refusesPermanentOrNegativeTimeouts() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var properties = new MiniAppAuthProperties();
            assertEquals(Duration.ofMinutes(30), properties.getMiniSessionTimeout());
            assertTrue(validator.validate(properties).isEmpty());
            for (Duration invalid : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), null}) {
                properties.setMiniSessionTimeout(invalid);
                assertFalse(validator.validate(properties).isEmpty());
            }
        }
    }
}
