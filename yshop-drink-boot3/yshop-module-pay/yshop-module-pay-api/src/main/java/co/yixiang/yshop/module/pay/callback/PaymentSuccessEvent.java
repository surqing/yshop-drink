package co.yixiang.yshop.module.pay.callback;

import java.time.LocalDateTime;

/** Verified, minimal server-internal event. No callback payload or user identity. */
public record PaymentSuccessEvent(
        Provider provider,
        String merchantDetailsId,
        String outTradeNo,
        String providerTransactionId,
        long totalFeeCents,
        String appid,
        String mchId,
        String resultCode,
        LocalDateTime receivedAt) {
    public enum Provider {
        WECHAT,
        ALIPAY,
        BALANCE,
        CASH
    }

    public PaymentSuccessEvent {
        if (provider == null
                || receivedAt == null
                || totalFeeCents <= 0
                || !"SUCCESS".equals(resultCode))
            throw new IllegalArgumentException("INVALID_PAYMENT_EVENT");
        require(outTradeNo, 64);
        require(providerTransactionId, 128);
        require(merchantDetailsId, 64);
        require(appid, 64);
        require(mchId, 64);
    }

    private static void require(String value, int length) {
        if (value == null
                || value.isBlank()
                || value.length() > length
                || !value.matches("[A-Za-z0-9_:.@-]+"))
            throw new IllegalArgumentException("INVALID_PAYMENT_EVENT");
    }

    public boolean external() {
        return provider == Provider.WECHAT || provider == Provider.ALIPAY;
    }
}
