package co.yixiang.yshop.module.order.service.payment;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PaymentRecord {
    private String id;
    private String orderId;
    private String attemptId;
    private String provider;
    private String merchantDetailsId;
    private String outTradeNo;
    private String providerTransactionId;
    private long amountCents;
    private String appid;
    private String mchId;
    private String resultCode;
    private String status;
    private String failureReason;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
}
