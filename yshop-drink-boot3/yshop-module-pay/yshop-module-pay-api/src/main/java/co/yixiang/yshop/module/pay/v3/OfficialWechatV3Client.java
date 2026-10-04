package co.yixiang.yshop.module.pay.v3;

import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.notification.*;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.model.Transaction;

import java.time.Instant;
import java.util.*;

/** All RSA operations and notification decryption are delegated to the official SDK. */
public final class OfficialWechatV3Client implements WechatV3Client {
    private final String appid, mchid, notifyUrl;
    private final RSAPublicKeyConfig config;
    private final JsapiService service;
    private final NotificationParser parser;

    public OfficialWechatV3Client(
            String appid,
            String mchid,
            String notifyUrl,
            RSAPublicKeyConfig config,
            HttpClient transport) {
        this.appid = appid;
        this.mchid = mchid;
        this.notifyUrl = notifyUrl;
        this.config = config;
        var builder = new JsapiService.Builder().config(config);
        builder.httpClient(
                transport != null
                        ? transport
                        : new DefaultHttpClientBuilder()
                                .config(config)
                                .disableRetryOnConnectionFailure()
                                .connectTimeoutMs(3000)
                                .readTimeoutMs(8000)
                                .writeTimeoutMs(8000)
                                .build());
        service = builder.build();
        parser = new NotificationParser(config);
    }

    public String appid() {
        return appid;
    }

    public String mchid() {
        return mchid;
    }

    public String notifyUrl() {
        return notifyUrl;
    }

    public String prepay(PrepayRequest request) {
        if (!appid.equals(request.getAppid())
                || !mchid.equals(request.getMchid())
                || !notifyUrl.equals(request.getNotifyUrl())) throw failure();
        try {
            String ref = service.prepay(request).getPrepayId();
            if (ref == null || !ref.matches("[A-Za-z0-9_:.@-]{1,128}")) throw failure();
            return ref;
        } catch (RuntimeException ignored) {
            throw failure();
        }
    }

    public Map<String, String> paymentParameters(String expectedAppid, String ref) {
        if (!appid.equals(expectedAppid) || ref == null || !ref.matches("[A-Za-z0-9_:.@-]{1,128}"))
            throw failure();
        try {
            String timestamp = Long.toString(Instant.now().getEpochSecond()),
                    nonce = UUID.randomUUID().toString().replace("-", ""),
                    pack = "prepay_id=" + ref;
            // Official JsapiServiceExtension message layout; the official Signer performs RSA.
            String signature =
                    config.createSigner()
                            .sign(appid + "\n" + timestamp + "\n" + nonce + "\n" + pack + "\n")
                            .getSign();
            return Map.of(
                    "timeStamp",
                    timestamp,
                    "nonceStr",
                    nonce,
                    "package",
                    pack,
                    "signType",
                    "RSA",
                    "paySign",
                    signature);
        } catch (RuntimeException ignored) {
            throw failure();
        }
    }

    public Transaction verifyNotification(RequestParam request) {
        try {
            return parser.parse(request, Transaction.class);
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("INVALID_WECHAT_V3_NOTIFICATION");
        }
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("WECHAT_V3_PROVIDER_UNAVAILABLE");
    }

    public String toString() {
        return "OfficialWechatV3Client[material omitted]";
    }
}
