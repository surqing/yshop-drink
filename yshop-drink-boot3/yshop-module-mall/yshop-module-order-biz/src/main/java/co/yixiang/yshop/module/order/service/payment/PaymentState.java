package co.yixiang.yshop.module.order.service.payment;

public enum PaymentState {
    RECEIVED,
    SUCCESS,
    PAYMENT_AMOUNT_MISMATCH,
    UNKNOWN_ORDER,
    TRANSACTION_ORDER_CONFLICT,
    PAYMENT_CONFLICT,
    RECONCILIATION_REQUIRED,
    UNSUPPORTED_RECHARGE,
    FAILED_RETRYABLE
}
