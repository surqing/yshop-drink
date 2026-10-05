package co.yixiang.yshop.module.order.service.payment;

import co.yixiang.yshop.module.order.dal.mysql.payment.PaymentMapper;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.service.payment.attempt.PaymentAttemptService;
import co.yixiang.yshop.module.pay.callback.*;
import co.yixiang.yshop.module.pay.mq.producer.PayNoticeProducer;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
public class PaymentFinalizationService implements PaymentCallbackService {
    private final PaymentInbox inbox;
    private final PaymentProcessor processor;
    private final PaymentMapper mapper;
    private final StoreOrderMapper orders;
    private final PayNoticeProducer producer;
    private final co.yixiang.yshop.module.member.service.wallet.WalletService wallets;
    private final PaymentAttemptService attempts;

    public PaymentFinalizationService(
            PaymentInbox inbox,
            PaymentProcessor processor,
            PaymentMapper mapper,
            StoreOrderMapper orders,
            PayNoticeProducer producer,
            co.yixiang.yshop.module.member.service.wallet.WalletService wallets,
            PaymentAttemptService attempts) {
        this.inbox = inbox;
        this.processor = processor;
        this.mapper = mapper;
        this.orders = orders;
        this.producer = producer;
        this.wallets = wallets;
        this.attempts = attempts;
    }

    @Override
    public PaymentResult accept(PaymentSuccessEvent event) {
        if (!event.external()) throw new IllegalArgumentException("EXTERNAL_PROVIDER_REQUIRED");
        PaymentInbox.Receipt receipt;
        try {
            receipt = inbox.capture(event);
        } catch (RuntimeException failure) {
            log.warn("payment inbox unavailable category={}", failure.getClass().getSimpleName());
            return PaymentResult.RETRY;
        }
        return dispatch(receipt);
    }

    /** Server-internal verified-event entry. Not wired to the legacy HTTP callback. */
    @co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore
    public PaymentResult acceptAttemptVerified(PaymentSuccessEvent event) {
        if (!event.external()) throw new IllegalArgumentException("EXTERNAL_PROVIDER_REQUIRED");
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("ATTEMPT_RECEIPT_REQUIRES_NO_CALLER_TRANSACTION");
        var attempt = attempts.resolve(event.outTradeNo());
        if (attempt == null) return PaymentResult.UNKNOWN_ORDER;
        PaymentInbox.Receipt receipt;
        try {
            receipt = inbox.captureAttempt(event, attempt);
        } catch (RuntimeException failure) {
            log.warn("attempt inbox unavailable category={}", failure.getClass().getSimpleName());
            return PaymentResult.RETRY;
        }
        return dispatch(receipt);
    }

    private PaymentResult dispatch(PaymentInbox.Receipt receipt) {
        if (receipt.conflict() != null) return receipt.conflict();
        // A hint only. Receipt already committed; database recovery does not depend on Redis.
        try {
            producer.sendPayNoticeMessage(receipt.id());
        } catch (RuntimeException failure) {
            log.warn(
                    "payment wakeup failed eventId={} category={}",
                    receipt.id(),
                    failure.getClass().getSimpleName());
        }
        try {
            return processPending(receipt.id());
        } catch (RuntimeException failure) {
            return PaymentResult.RETRY;
        }
    }

    public PaymentResult processPending(String eventId) {
        try {
            return processor.process(eventId, false);
        } catch (RuntimeException failure) {
            try {
                inbox.failed(eventId);
            } catch (RuntimeException auditFailure) {
                log.warn("payment retry marker failed eventId={}", eventId);
            }
            // Propagate to the stream listener: failed processing must not XACK.
            throw failure;
        }
    }

    /** Minimal compatibility for server-side cash/balance callers, not external callbacks. */
    @Transactional(rollbackFor = Exception.class)
    public PaymentResult finalizeInternal(String orderId, PaymentSuccessEvent.Provider provider) {
        if (provider != PaymentSuccessEvent.Provider.CASH
                && provider != PaymentSuccessEvent.Provider.BALANCE)
            throw new IllegalArgumentException("INTERNAL_PROVIDER_REQUIRED");
        PaymentOrder order = orders.lockPaymentOrder(orderId);
        if (order == null) throw new IllegalArgumentException("UNKNOWN_ORDER");
        attempts.assertInternalFundingAllowed(order.getOrderId());
        if (provider == PaymentSuccessEvent.Provider.BALANCE)
            wallets.requireOrderDebit(order.getUid(), order.getOrderId(), order.getPayPrice());
        var event =
                new PaymentSuccessEvent(
                        provider,
                        "internal",
                        order.getOrderId(),
                        "internal:" + order.getOrderId(),
                        Money.cents(order.getPayPrice()),
                        "internal",
                        "internal",
                        "SUCCESS",
                        LocalDateTime.now());
        // BALANCE receipt, wallet movement, ledger and fulfillment share one
        // transaction/connection.
        // External receipts remain independently durable. CASH compatibility is unchanged.
        var receipt =
                provider == PaymentSuccessEvent.Provider.BALANCE
                        ? inbox.captureBalance(event)
                        : inbox.capture(event);
        if (receipt.conflict() != null) return receipt.conflict();
        return processor.process(receipt.id(), true);
    }

    @Scheduled(fixedDelayString = "${yshop.payment.recovery-delay-ms:30000}")
    public void recover() {
        for (String id : mapper.pending()) {
            try {
                processPending(id);
            } catch (RuntimeException failure) {
                log.warn(
                        "payment recovery deferred eventId={} category={}",
                        id,
                        failure.getClass().getSimpleName());
            }
        }
    }
}
