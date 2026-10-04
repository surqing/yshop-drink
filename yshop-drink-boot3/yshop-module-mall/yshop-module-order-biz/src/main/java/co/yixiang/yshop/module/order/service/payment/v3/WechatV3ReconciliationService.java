package co.yixiang.yshop.module.order.service.payment.v3;

import co.yixiang.yshop.module.order.service.payment.attempt.*;
import co.yixiang.yshop.module.pay.callback.PaymentResult;
import co.yixiang.yshop.module.pay.v3.*;

import com.wechat.pay.java.service.payments.model.Transaction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** Trusted internal SDK recovery; never exposed as a client-driven payment endpoint. */
@Service
public class WechatV3ReconciliationService {
    public enum Result {
        DISABLED,
        SKIPPED,
        UNCERTAIN,
        NOTPAY,
        SUCCESS,
        TERMINATED,
        RECONCILIATION
    }

    private final PaymentAttemptService attempts;
    private final WechatV3ClientFactory clients;
    private final WechatV3PaymentService payments;
    private final boolean enabled;
    private final int minimumAge, leaseSeconds;

    public WechatV3ReconciliationService(
            PaymentAttemptService attempts,
            WechatV3ClientFactory clients,
            WechatV3PaymentService payments,
            @Value("${yshop.pay.wechat-v3.enabled:false}") boolean live,
            @Value("${yshop.pay.wechat-v3.reconciliation-enabled:false}") boolean recovery,
            @Value("${yshop.pay.wechat-v3.reconciliation-minimum-age-seconds:60}") int minimumAge,
            @Value("${yshop.pay.wechat-v3.reconciliation-lease-seconds:60}") int leaseSeconds) {
        this.attempts = attempts;
        this.clients = clients;
        this.payments = payments;
        this.enabled = live && recovery;
        this.minimumAge = Math.max(0, minimumAge);
        if (leaseSeconds < 30 || leaseSeconds > 600)
            throw new IllegalArgumentException("INVALID_RECOVERY_LEASE");
        this.leaseSeconds = leaseSeconds;
    }

    public Result reconcile(String id, boolean closeUnpaid) {
        if (!enabled) return Result.DISABLED;
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("RECOVERY_REQUIRES_NO_CALLER_TRANSACTION");
        String token = UUID.randomUUID().toString().replace("-", "");
        PaymentAttempt a = attempts.claimReconciliation(id, token, leaseSeconds, minimumAge);
        if (a == null) return Result.SKIPPED;
        try {
            WechatV3Client client = clients.forMerchant(a.getMerchantDetailsId());
            if (!a.getAppid().equals(client.appid())
                    || !a.getMerchantIdentity().equals(client.mchid())) return Result.UNCERTAIN;
            Transaction query = client.query(a.getProviderOrderReference());
            if (!matches(a, query)) return Result.UNCERTAIN;
            switch (query.getTradeState()) {
                case SUCCESS:
                    PaymentResult result =
                            payments.verifiedSuccess(a.getMerchantDetailsId(), query);
                    return result == PaymentResult.FIRST_SUCCESS
                                    || result == PaymentResult.IDEMPOTENT_DUPLICATE
                            ? Result.SUCCESS
                            : result == PaymentResult.RETRY
                                    ? Result.UNCERTAIN
                                    : Result.RECONCILIATION;
                case NOTPAY:
                    if (!closeUnpaid) return Result.NOTPAY;
                    if (!attempts.mayClose(id, token)) return Result.SKIPPED;
                    // Provider atomically rejects closing a paid order; no local lock spans I/O.
                    client.close(a.getProviderOrderReference());
                    return attempts.confirmRemoteTerminal(id, token, "CLOSED")
                            ? Result.TERMINATED
                            : Result.SKIPPED;
                case CLOSED:
                case REVOKED:
                case PAYERROR:
                    return attempts.confirmRemoteTerminal(id, token, query.getTradeState().name())
                            ? Result.TERMINATED
                            : Result.SKIPPED;
                default:
                    return Result.UNCERTAIN;
            }
        } catch (RuntimeException ignored) {
            // Do not infer terminal status from an error body, timeout or transport failure.
            return Result.UNCERTAIN;
        } finally {
            attempts.releaseReconciliation(id, token);
        }
    }

    private static boolean matches(PaymentAttempt a, Transaction t) {
        return t != null
                && t.getTradeState() != null
                && Transaction.TradeTypeEnum.JSAPI == t.getTradeType()
                && a.getProviderOrderReference().equals(t.getOutTradeNo())
                && a.getAppid().equals(t.getAppid())
                && a.getMerchantIdentity().equals(t.getMchid())
                && t.getAmount() != null
                && "CNY".equals(t.getAmount().getCurrency())
                && t.getAmount().getTotal() != null
                && a.getAmountCents() == t.getAmount().getTotal().longValue();
    }

    public java.util.List<String> candidates(int batch) {
        return enabled ? attempts.reconciliationCandidates(minimumAge, batch) : java.util.List.of();
    }
}
