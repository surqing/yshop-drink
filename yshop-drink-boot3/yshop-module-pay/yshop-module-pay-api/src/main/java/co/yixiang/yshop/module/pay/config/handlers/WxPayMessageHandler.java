package co.yixiang.yshop.module.pay.config.handlers;

import co.yixiang.yshop.module.pay.callback.*;

import com.egzosn.pay.common.api.*;
import com.egzosn.pay.common.bean.PayOutMessage;
import com.egzosn.pay.wx.api.WxPayService;
import com.egzosn.pay.wx.bean.WxPayMessage;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class WxPayMessageHandler implements PayMessageHandler<WxPayMessage, PayService> {
    private final PaymentCallbackService callbacks;

    public WxPayMessageHandler(PaymentCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    /** Legacy SDK routing lacks a trusted merchant binding: fail closed. */
    @Override
    public PayOutMessage handle(
            WxPayMessage message, Map<String, Object> context, PayService service) {
        return service.getPayOutMessage("FAIL", "Use verified callback entry");
    }

    public PayOutMessage handleCallback(
            String detailsId, Map<String, Object> body, WxPayService service) {
        var config = service.getPayConfigStorage();
        Object signature = body.get("sign");
        // Guard SDK's unsigned refund bypass and raw-body error logging before verify().
        // SDK sandbox verification may fetch a remote key: not enabled by this local baseline.
        if (body.containsKey("req_info")
                || config.isTest()
                || !"SUCCESS".equals(body.get("return_code"))
                || !"SUCCESS".equals(body.get("result_code"))
                || !(signature instanceof String)
                || !((String) signature).matches("[A-Fa-f0-9]{32}|[A-Fa-f0-9]{64}")
                || (body.containsKey("sign_type")
                        && !sameSignType(config.getSignType(), body.get("sign_type")))
                || !matches(config.getAppid(), body.get("appid"))
                || !matches(config.getMchId(), body.get("mch_id"))
                || (body.containsKey("fee_type") && !"CNY".equals(body.get("fee_type")))
                || !service.verify(body))
            return service.getPayOutMessage("FAIL", "Invalid payment notification");
        var event =
                new PaymentSuccessEvent(
                        PaymentSuccessEvent.Provider.WECHAT,
                        detailsId,
                        (String) body.get("out_trade_no"),
                        (String) body.get("transaction_id"),
                        Money.positiveCents((String) body.get("total_fee")),
                        config.getAppid(),
                        config.getMchId(),
                        "SUCCESS",
                        LocalDateTime.now());
        boolean ack = callbacks.accept(event).acknowledge();
        return service.getPayOutMessage(ack ? "SUCCESS" : "FAIL", ack ? "OK" : "Retry later");
    }

    private static boolean sameSignType(String expected, Object actual) {
        return actual instanceof String
                && expected != null
                && expected.replace("-", "").equals(((String) actual).replace("-", ""));
    }

    private static boolean matches(String expected, Object actual) {
        return expected != null && !expected.isBlank() && expected.equals(actual);
    }
}
