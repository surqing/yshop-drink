package co.yixiang.yshop.module.pay.callback;

public enum PaymentResult {
    FIRST_SUCCESS(true),
    IDEMPOTENT_DUPLICATE(true),
    RECONCILIATION_REQUIRED(true),
    REJECTED(true),
    UNKNOWN_ORDER(false),
    RETRY(false);
    private final boolean acknowledge;

    PaymentResult(boolean acknowledge) {
        this.acknowledge = acknowledge;
    }

    /** Rejections are ACKed only after a durable audit record, never as a paid order. */
    public boolean acknowledge() {
        return acknowledge;
    }
}
