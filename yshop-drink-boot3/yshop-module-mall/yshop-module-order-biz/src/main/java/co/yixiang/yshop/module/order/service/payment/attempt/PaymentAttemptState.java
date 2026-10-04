package co.yixiang.yshop.module.order.service.payment.attempt;

public enum PaymentAttemptState {
    CREATED,
    PREPAY_CREATED,
    PAID,
    FAILED,
    EXPIRED,
    CANCELED;

    public boolean active() {
        return this == CREATED || this == PREPAY_CREATED;
    }

    public boolean termination() {
        return this == FAILED || this == EXPIRED || this == CANCELED;
    }
}
