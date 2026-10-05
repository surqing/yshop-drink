package co.yixiang.yshop.module.store.service.printer.yilianyun;

import cn.hutool.crypto.digest.DigestUtil;
import co.yixiang.yshop.module.store.service.printer.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;

@Component
public class YilianyunPrinterProvider implements PrinterProvider {
    private final YilianyunProperties settings;
    private final YilianyunTransport transport;
    private final YilianyunTokenStore tokens;
    public YilianyunPrinterProvider(YilianyunProperties settings,YilianyunTransport transport,YilianyunTokenStore tokens) {
        this.settings=settings; this.transport=transport; this.tokens=tokens;
    }
    public ProviderInfo info() {
        return new ProviderInfo("YILIAN_YUN","易联云",List.of("CLOUD"),
                List.of(new Field("terminalKey","终端密钥",true,false)),
                new Capabilities(true,true,true,true),settings.configured());
    }
    public void validate(String code,String connection,Map<String,String> config) {
        if(!code.matches("[0-9]{5,32}")||!"CLOUD".equals(connection)||config.keySet().stream().anyMatch(key->!key.equals("terminalKey"))
                ||(config.containsKey("terminalKey")&&!config.get("terminalKey").matches("[a-zA-Z0-9]{4,128}")))
            throw new PrinterFailure("INVALID_CONFIGURATION",false);
    }
    private Map<String,String> common() {
        if(!settings.configured()) throw new PrinterFailure("PROVIDER_NOT_CONFIGURED",false);
        String timestamp=String.valueOf(Instant.now().getEpochSecond());
        Map<String,String> form=new LinkedHashMap<>(); form.put("client_id",settings.getClientId());
        form.put("timestamp",timestamp); form.put("id",UUID.randomUUID().toString());
        form.put("sign",DigestUtil.md5Hex(settings.getClientId()+timestamp+settings.getClientSecret())); return form;
    }
    private String token() {
        String key=DigestUtil.sha256Hex(settings.getClientId()+":"+settings.getClientSecret());
        return tokens.get(key,()->{
            Map<String,String> form=common(); form.put("grant_type","client_credentials"); form.put("scope","all");
            var body=transport.post("/oauth/oauth",form);
            if(!body.path("access_token").isTextual()||body.path("access_token").asText().isBlank())
                throw new PrinterFailure("PROVIDER_INVALID_RESPONSE",false);
            return body.path("access_token").asText();
        });
    }
    private Map<String,String> device(String code) {
        Map<String,String> form=common(); form.put("access_token",token()); form.put("machine_code",code); return form;
    }
    public void authorize(String code,String name,Map<String,String> config) {
        if(!config.containsKey("terminalKey")) throw new PrinterFailure("TERMINAL_KEY_REQUIRED",false);
        Map<String,String> form=device(code); form.put("msign",config.get("terminalKey")); form.put("print_name",plain(name));
        transport.post("/printer/addprinter",form);
    }
    public void deauthorize(String code) { transport.post("/printer/deleteprinter",device(code)); }
    public DeviceState status(String code) {
        var body=transport.post("/printer/getprintstatus",device(code));
        if(!body.path("state").isIntegralNumber()) throw new PrinterFailure("PROVIDER_INVALID_RESPONSE",false);
        return switch(body.path("state").asInt()) { case 0->DeviceState.OFFLINE; case 1->DeviceState.ONLINE;
            case 2->DeviceState.PAPER_OUT; default->DeviceState.UNKNOWN; };
    }
    public PrintResult print(String code,String requestId,Receipt receipt) {
        Map<String,String> form=device(code); form.put("origin_id",requestId); form.put("idempotence","1");
        form.put("content",render(receipt));
        var body=transport.post("/print/index",form);
        if(!body.path("id").isIntegralNumber()) throw new PrinterFailure("PROVIDER_INVALID_RESPONSE",true);
        return new PrintResult(body.path("id").asText());
    }
    static String render(Receipt receipt) {
        if(receipt.copies()!=1) throw new PrinterFailure("UNSUPPORTED_CAPABILITY",false);
        StringBuilder text=new StringBuilder("<FB>").append(plain(receipt.title())).append("</FB>\n");
        for(String line:receipt.lines()) text.append(plain(line)).append('\n');
        return text.append("\n\n").toString();
    }
    private static String plain(String value) {
        // Device/shop names must never inject printer commands (cut, copies, sound, etc.).
        return value.replace('<','＜').replace('>','＞').replace('*','＊').replace('@','＠').replaceAll("[\\p{Cc}]"," ");
    }
}
