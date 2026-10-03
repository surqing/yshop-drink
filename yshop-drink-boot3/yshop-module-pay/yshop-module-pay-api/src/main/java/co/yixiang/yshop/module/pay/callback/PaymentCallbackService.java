package co.yixiang.yshop.module.pay.callback;

/** Only verified external callbacks may enter this boundary. */
public interface PaymentCallbackService {
    PaymentResult accept(PaymentSuccessEvent event);
}
