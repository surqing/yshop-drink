package co.yixiang.yshop.module.store.service.printer.yilianyun;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="yshop.printer.yilianyun")
@Getter @Setter
public class YilianyunProperties {
    private boolean enabled;
    private String clientId="";
    private String clientSecret="";
    public boolean configured() { return enabled&&!clientId.isBlank()&&!clientSecret.isBlank(); }
}
