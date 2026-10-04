package co.yixiang.yshop.module.pay.v3;

import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.model.Transaction;

import java.util.Map;

public interface WechatV3Client {
    String appid();

    String mchid();

    String notifyUrl();

    String prepay(PrepayRequest request);

    Map<String, String> paymentParameters(String appid, String prepayReference);

    Transaction verifyNotification(RequestParam request);
}
