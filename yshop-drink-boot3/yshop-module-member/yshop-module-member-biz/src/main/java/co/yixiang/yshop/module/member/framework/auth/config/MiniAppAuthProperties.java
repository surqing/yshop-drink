package co.yixiang.yshop.module.member.framework.auth.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Local cache lifetime for the session key used by legacy phone authorization. */
@Component
@ConfigurationProperties(prefix = "yshop.member.auth")
@Validated
@Data
public class MiniAppAuthProperties {

    @NotNull
    private Duration miniSessionTimeout = Duration.ofMinutes(30);

    @AssertTrue(message = "mini-session-timeout must be positive")
    public boolean isMiniSessionTimeoutPositive() {
        return miniSessionTimeout != null && miniSessionTimeout.compareTo(Duration.ZERO) > 0;
    }
}
