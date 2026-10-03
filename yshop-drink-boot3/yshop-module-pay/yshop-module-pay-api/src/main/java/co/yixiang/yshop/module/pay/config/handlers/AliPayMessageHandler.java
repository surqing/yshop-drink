package co.yixiang.yshop.module.pay.config.handlers;

import co.yixiang.yshop.module.pay.callback.*;

import com.egzosn.pay.ali.api.AliPayService;
import com.egzosn.pay.ali.bean.AliPayMessage;
import com.egzosn.pay.common.api.PayMessageHandler;
import com.egzosn.pay.common.bean.PayOutMessage;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class AliPayMessageHandler implements PayMessageHandler<AliPayMessage, AliPayService> {
    private final PaymentCallbackService callbacks;

    public AliPayMessageHandler(PaymentCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public PayOutMessage handle(
            AliPayMessage message, Map<String, Object> context, AliPayService service) {
        return service.getPayOutMessage("fail", "Use verified callback entry");
    }

    public PayOutMessage handleCallback(
            String detailsId, Map<String, Object> body, AliPayService service) {
        var config = service.getPayConfigStorage();
        Object signature = body.get("sign");
        if (!("TRADE_SUCCESS".equals(body.get("trade_status"))
                        || "TRADE_FINISHED".equals(body.get("trade_status")))
                || !(signature instanceof String)
                || ((String) signature).isBlank()
                || config.isTest()
                || config.getAppid() == null
                || !config.getAppid().equals(body.get("app_id"))
                || config.getSeller() == null
                || config.getSeller().isBlank()
                || !config.getSeller().equals(body.get("seller_id"))
                || !service.verify(body))
            return service.getPayOutMessage("fail", "Invalid payment notification");
        var event =
                new PaymentSuccessEvent(
                        PaymentSuccessEvent.Provider.ALIPAY,
                        detailsId,
                        (String) body.get("out_trade_no"),
                        (String) body.get("trade_no"),
                        Money.cents(new BigDecimal((String) body.get("total_amount"))),
                        config.getAppid(),
                        config.getSeller(),
                        "SUCCESS",
                        LocalDateTime.now());
        boolean ack = callbacks.accept(event).acknowledge();
        return service.getPayOutMessage(ack ? "success" : "fail", ack ? "OK" : "Retry later");
    }
}
