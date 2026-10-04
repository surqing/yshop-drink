package co.yixiang.yshop.module.order.service.payment.attempt;

import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;
import co.yixiang.yshop.module.order.dal.mysql.payment.*;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.service.payment.*;
import co.yixiang.yshop.module.pay.callback.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** No HTTP endpoint or SDK calls. Merchant selection is supplied by trusted server code. */
@Service
public class PaymentAttemptService {
    @org.springframework.beans.factory.annotation.Value("${yshop.pay.wechat-v3.enabled:false}")
    private boolean wechatLiveEnabled;

    private final PaymentAttemptMapper attempts;
    private final StoreOrderMapper orders;
    private final PaymentMerchantIdentityMapper merchants;

    public PaymentAttemptService(
            PaymentAttemptMapper attempts,
            StoreOrderMapper orders,
            PaymentMerchantIdentityMapper merchants) {
        this.attempts = attempts;
        this.orders = orders;
        this.merchants = merchants;
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public PaymentAttempt createOrGet(
            Long uid,
            String orderId,
            PaymentSuccessEvent.Provider provider,
            String merchantDetailsId,
            String key) {
        if (wechatLiveEnabled && provider == PaymentSuccessEvent.Provider.ALIPAY)
            throw failure("ALIPAY_ATTEMPT_NOT_LIVE_READY");
        reference(orderId, 64);
        reference(merchantDetailsId, 64);
        reference(key, 128);
        if (provider != PaymentSuccessEvent.Provider.WECHAT
                && provider != PaymentSuccessEvent.Provider.ALIPAY)
            throw failure("EXTERNAL_PROVIDER_REQUIRED");
        PaymentOrder order = orders.lockPaymentOrder(orderId);
        payable(order, uid);
        long amount = Money.cents(order.getPayPrice());
        MerchantIdentity merchant = merchants.find(merchantDetailsId);
        if (merchant == null
                || !merchantDetailsId.equals(merchant.getDetailsId())
                || !Objects.equals(
                        provider == PaymentSuccessEvent.Provider.WECHAT ? "wxPay" : "aliPay",
                        merchant.getPayType())) throw failure("INVALID_PAYMENT_MERCHANT");
        String identity =
                provider == PaymentSuccessEvent.Provider.WECHAT
                        ? merchant.getMchId()
                        : merchant.getSeller();
        reference(merchant.getAppid(), 64);
        reference(identity, 64);
        PaymentAttempt prior = attempts.key(orderId, key);
        if (prior != null) {
            if (!uid.equals(prior.getUid())
                    || !provider.name().equals(prior.getProvider())
                    || !merchantDetailsId.equals(prior.getMerchantDetailsId())
                    || amount != prior.getAmountCents()
                    || !merchant.getAppid().equals(prior.getAppid())
                    || !identity.equals(prior.getMerchantIdentity()))
                throw failure("ATTEMPT_IDEMPOTENCY_CONFLICT");
            return prior;
        }
        if (attempts.active(orderId) != null) throw failure("ACTIVE_PAYMENT_ATTEMPT_EXISTS");
        PaymentAttempt a = new PaymentAttempt();
        a.setAttemptId(UUID.randomUUID().toString().replace("-", ""));
        a.setProviderOrderReference(a.getAttemptId());
        a.setUid(uid);
        a.setOrderId(orderId);
        a.setIdempotencyKey(key);
        a.setProvider(provider.name());
        a.setMerchantDetailsId(merchantDetailsId);
        a.setAmountCents(amount);
        a.setAppid(merchant.getAppid());
        a.setMerchantIdentity(identity);
        if (attempts.insert(a) != 1) throw failure("ATTEMPT_INSERT_FAILED");
        return attempts.lock(a.getAttemptId());
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public PaymentAttempt createWechatForPay(Long uid, String orderId, String merchant) {
        payable(orders.lockPaymentOrder(orderId), uid);
        PaymentAttempt latest = attempts.latest(orderId);
        String key = "wechat-v3-jsapi";
        if (latest != null)
            key =
                    PaymentAttemptState.valueOf(latest.getStatus()).active()
                            ? latest.getIdempotencyKey()
                            : "wechat-v3-jsapi:" + latest.getAttemptId();
        return createOrGet(uid, orderId, PaymentSuccessEvent.Provider.WECHAT, merchant, key);
    }

    @TenantIgnore
    public PaymentAttempt read(Long uid, String id) {
        reference(id, 32);
        return owned(attempts.find(id), uid);
    }

    @TenantIgnore
    public PaymentAttempt resolve(String providerReference) {
        reference(providerReference, 64);
        return attempts.reference(providerReference);
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public PaymentAttempt recordPrepay(Long uid, String id, String ref) {
        reference(ref, 128);
        PaymentAttempt hint = read(uid, id);
        PaymentOrder order = orders.lockPaymentOrder(hint.getOrderId());
        payable(order, uid);
        PaymentAttempt a = owned(attempts.lock(id), uid);
        if (a.getAmountCents() != Money.cents(order.getPayPrice()))
            throw failure("ATTEMPT_AMOUNT_CHANGED");
        if ("PREPAY_CREATED".equals(a.getStatus()) && ref.equals(a.getPrepayReference())) return a;
        if (attempts.prepay(id, ref) != 1) throw failure("ATTEMPT_STATE_CONFLICT");
        return attempts.lock(id);
    }

    /** Commits before provider I/O. An uncertain request must never be automatically repeated. */
    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public boolean claimPrepay(Long uid, String id) {
        PaymentAttempt hint = read(uid, id);
        PaymentOrder order = orders.lockPaymentOrder(hint.getOrderId());
        payable(order, uid);
        PaymentAttempt a = owned(attempts.lock(id), uid);
        if (a.getAmountCents() != Money.cents(order.getPayPrice()))
            throw failure("ATTEMPT_AMOUNT_CHANGED");
        if (!"CREATED".equals(a.getStatus()) || a.getPrepayRequestedAt() != null) return false;
        return attempts.claimPrepay(id) == 1;
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public PaymentAttempt terminateCreated(Long uid, String id, PaymentAttemptState state) {
        if (state == null || !state.termination()) throw failure("INVALID_ATTEMPT_TERMINATION");
        PaymentAttempt a = lockOwned(uid, id);
        if (state.name().equals(a.getStatus())) return a;
        if (attempts.terminate(id, state.name()) != 1) throw failure("ATTEMPT_STATE_CONFLICT");
        return attempts.lock(id);
    }

    /** Trusted recovery worker only: no endpoint and no provider I/O in this transaction. */
    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public PaymentAttempt claimReconciliation(
            String id, String token, int leaseSeconds, int minimumAgeSeconds) {
        reference(token, 32);
        if (leaseSeconds < 30 || leaseSeconds > 600 || minimumAgeSeconds < 0)
            throw failure("INVALID_RECOVERY_LEASE");
        PaymentAttempt hint = attempts.find(id);
        if (hint == null) return null;
        PaymentOrder order = orders.lockPaymentOrder(hint.getOrderId());
        PaymentAttempt a = attempts.lock(id);
        if (order == null || !a.getUid().equals(order.getUid())) return null;
        if (attempts.claimReconciliation(id, token, leaseSeconds, minimumAgeSeconds) != 1)
            return null;
        return attempts.lock(id);
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmRemoteTerminal(String id, String token, String remoteState) {
        if (!java.util.Set.of("CLOSED", "REVOKED", "PAYERROR").contains(remoteState))
            throw failure("INVALID_REMOTE_TERMINAL");
        PaymentAttempt hint = attempts.find(id);
        if (hint == null) return false;
        PaymentOrder order = orders.lockPaymentOrder(hint.getOrderId());
        PaymentAttempt a = attempts.lock(id);
        if (order == null
                || !a.getUid().equals(order.getUid())
                || !"WECHAT".equals(a.getProvider())) return false;
        return attempts.remoteTerminal(
                        id,
                        token,
                        "PAYERROR".equals(remoteState) ? "FAILED" : "CANCELED",
                        remoteState)
                == 1;
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public boolean mayClose(String id, String token) {
        PaymentAttempt hint = attempts.find(id);
        if (hint == null) return false;
        PaymentOrder order = orders.lockPaymentOrder(hint.getOrderId());
        PaymentAttempt a = attempts.lock(id);
        return order != null
                && a.getUid().equals(order.getUid())
                && PaymentAttemptState.valueOf(a.getStatus()).active()
                && token.equals(a.getReconciliationToken())
                && attempts.leaseCurrent(id, token) > 0;
    }

    @TenantIgnore
    public void releaseReconciliation(String id, String token) {
        attempts.releaseReconciliation(id, token);
    }

    @TenantIgnore
    public java.util.List<String> reconciliationCandidates(int age, int limit) {
        return attempts.reconciliationCandidates(
                Math.max(0, age), Math.min(100, Math.max(1, limit)));
    }

    private PaymentAttempt lockOwned(Long uid, String id) {
        PaymentAttempt hint = read(uid, id);
        PaymentOrder order = orders.lockPaymentOrder(hint.getOrderId());
        if (order == null || !uid.equals(order.getUid())) throw failure("INVALID_ATTEMPT_ORDER");
        return owned(attempts.lock(id), uid);
    }

    private static PaymentAttempt owned(PaymentAttempt a, Long uid) {
        if (a == null || uid == null || !uid.equals(a.getUid()))
            throw failure("UNKNOWN_PAYMENT_ATTEMPT");
        return a;
    }

    private static void payable(PaymentOrder order, Long uid) {
        if (order == null
                || uid == null
                || !uid.equals(order.getUid())
                || !Integer.valueOf(0).equals(order.getPaid())
                || !Integer.valueOf(0).equals(order.getStatus())
                || !Integer.valueOf(0).equals(order.getRefundStatus())
                || !Integer.valueOf(0).equals(order.getIsSystemDel())
                || Boolean.TRUE.equals(order.getDeleted())) throw failure("ORDER_NOT_PAYABLE");
    }

    public static boolean matches(PaymentAttempt a, PaymentRecord e, PaymentOrder o) {
        return a != null
                && a.getOrderId().equals(e.getOrderId())
                && a.getOrderId().equals(o.getOrderId())
                && a.getUid().equals(o.getUid())
                && a.getProviderOrderReference().equals(e.getOutTradeNo())
                && a.getProvider().equals(e.getProvider())
                && a.getMerchantDetailsId().equals(e.getMerchantDetailsId())
                && a.getAppid().equals(e.getAppid())
                && a.getMerchantIdentity().equals(e.getMchId())
                && "CNY".equals(a.getCurrency());
    }

    private static void reference(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.matches("[A-Za-z0-9_:.@-]+")) throw failure("INVALID_ATTEMPT_REFERENCE");
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
