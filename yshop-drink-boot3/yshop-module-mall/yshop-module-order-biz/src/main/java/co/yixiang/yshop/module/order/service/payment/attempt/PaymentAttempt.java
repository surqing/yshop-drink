package co.yixiang.yshop.module.order.service.payment.attempt;

import lombok.Data;

import java.time.LocalDateTime;

/** Internal metadata only. Never contains credentials or raw provider payloads. */
@Data
public class PaymentAttempt {
    private String attemptId;
    private String orderId;
    private Long uid;
    private String idempotencyKey;
    private String provider;
    private String merchantDetailsId;
    private long amountCents;
    private String currency;
    private String appid;
    private String merchantIdentity;
    private String providerOrderReference;
    private String prepayReference;
    private String providerTransactionId;
    private String status;
    private String paymentEventId;
    private LocalDateTime paidAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
