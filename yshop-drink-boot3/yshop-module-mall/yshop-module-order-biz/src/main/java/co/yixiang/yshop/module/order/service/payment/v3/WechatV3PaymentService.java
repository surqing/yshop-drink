package co.yixiang.yshop.module.order.service.payment.v3;

import co.yixiang.yshop.module.order.service.payment.PaymentFinalizationService;
import co.yixiang.yshop.module.order.service.payment.attempt.*;
import co.yixiang.yshop.module.pay.callback.*;
import co.yixiang.yshop.module.pay.v3.*;

import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class WechatV3PaymentService {
    private final PaymentAttemptService attempts;
    private final WechatV3ClientFactory clients;
    private final PaymentFinalizationService finalization;

    public WechatV3PaymentService(
            PaymentAttemptService attempts,
            WechatV3ClientFactory clients,
            PaymentFinalizationService finalization) {
        this.attempts = attempts;
        this.clients = clients;
        this.finalization = finalization;
    }

    public Map<String, String> pay(
            Long uid, String orderId, String selectedMerchant, String serverOpenid) {
        noTransaction();
        if (serverOpenid == null || !serverOpenid.matches("[A-Za-z0-9_-]{1,128}"))
            throw failure("WECHAT_MEMBER_ID_REQUIRED");
        WechatV3Client client = clients.forMerchant(selectedMerchant);
        var a = attempts.createWechatForPay(uid, orderId, selectedMerchant);
        identity(client, a);
        if ("PREPAY_CREATED".equals(a.getStatus()))
            return client.paymentParameters(a.getAppid(), a.getPrepayReference());
        int total = Math.toIntExact(a.getAmountCents());
        if (!attempts.claimPrepay(uid, a.getAttemptId()))
            throw failure("WECHAT_PREPAY_RESULT_UNCERTAIN");
        PrepayRequest request = new PrepayRequest();
        request.setAppid(a.getAppid());
        request.setMchid(a.getMerchantIdentity());
        request.setOutTradeNo(a.getProviderOrderReference());
        request.setNotifyUrl(client.notifyUrl());
        request.setDescription("商品购买");
        Amount amount = new Amount();
        amount.setTotal(total);
        amount.setCurrency(a.getCurrency());
        request.setAmount(amount);
        Payer payer = new Payer();
        payer.setOpenid(serverOpenid);
        request.setPayer(payer);
        String prepay;
        try {
            prepay = client.prepay(request);
        } catch (RuntimeException ignored) {
            throw failure("WECHAT_PREPAY_RESULT_UNCERTAIN");
        }
        // No response parameters before the provider reference has committed to the attempt.
        PaymentAttempt stored = attempts.recordPrepay(uid, a.getAttemptId(), prepay);
        return client.paymentParameters(stored.getAppid(), stored.getPrepayReference());
    }

    public PaymentResult callback(String verifierMerchant, RequestParam request) {
        noTransaction();
        WechatV3Client client = clients.forMerchant(verifierMerchant);
        Transaction transaction = client.verifyNotification(request);
        return verifiedSuccess(verifierMerchant, transaction);
    }

    /** Internal trusted SDK query/notification result only; no HTTP transaction input. */
    public PaymentResult verifiedSuccess(String verifierMerchant, Transaction transaction) {
        noTransaction();
        WechatV3Client client = clients.forMerchant(verifierMerchant);
        if (transaction == null
                || transaction.getTradeState() != Transaction.TradeStateEnum.SUCCESS
                || transaction.getTradeType() != Transaction.TradeTypeEnum.JSAPI
                || transaction.getAmount() == null
                || !"CNY".equals(transaction.getAmount().getCurrency())
                || transaction.getAmount().getTotal() == null
                || transaction.getAmount().getTotal() <= 0
                || !client.appid().equals(transaction.getAppid())
                || !client.mchid().equals(transaction.getMchid()))
            throw failure("INVALID_WECHAT_V3_NOTIFICATION");
        var a = attempts.resolve(transaction.getOutTradeNo());
        if (a == null) return PaymentResult.UNKNOWN_ORDER;
        if (!"WECHAT".equals(a.getProvider()) || !verifierMerchant.equals(a.getMerchantDetailsId()))
            throw failure("INVALID_WECHAT_V3_ATTEMPT");
        identity(client, a);
        return finalization.acceptAttemptVerified(
                new PaymentSuccessEvent(
                        PaymentSuccessEvent.Provider.WECHAT,
                        a.getMerchantDetailsId(),
                        transaction.getOutTradeNo(),
                        transaction.getTransactionId(),
                        transaction.getAmount().getTotal(),
                        transaction.getAppid(),
                        transaction.getMchid(),
                        "SUCCESS",
                        LocalDateTime.now()));
    }

    private static void identity(WechatV3Client c, PaymentAttempt a) {
        if (!c.appid().equals(a.getAppid()) || !c.mchid().equals(a.getMerchantIdentity()))
            throw failure("WECHAT_ATTEMPT_MERCHANT_MISMATCH");
    }

    private static void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw failure("WECHAT_V3_REQUIRES_NO_CALLER_TRANSACTION");
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
