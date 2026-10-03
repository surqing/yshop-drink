package co.yixiang.yshop.module.order.service.payment;

import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;
import co.yixiang.yshop.module.order.dal.mysql.payment.PaymentMapper;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.pay.callback.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
@Slf4j
public class PaymentProcessor {
    private final PaymentMapper payments;
    private final StoreOrderMapper orders;
    private final PaymentEffects effects;

    public PaymentProcessor(
            PaymentMapper payments, StoreOrderMapper orders, PaymentEffects effects) {
        this.payments = payments;
        this.orders = orders;
        this.effects = effects;
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public PaymentResult process(String eventId, boolean internal) {
        PaymentRecord event = payments.lockEvent(eventId);
        if (event == null) throw new IllegalArgumentException("UNKNOWN_PAYMENT_EVENT");
        boolean external =
                "WECHAT".equals(event.getProvider()) || "ALIPAY".equals(event.getProvider());
        if (!external && !internal)
            throw new IllegalArgumentException("INTERNAL_PAYMENT_NOT_REPLAYABLE");
        PaymentState state = PaymentState.valueOf(event.getStatus());
        if (state == PaymentState.SUCCESS) return PaymentResult.IDEMPOTENT_DUPLICATE;
        if (state == PaymentState.RECONCILIATION_REQUIRED || state == PaymentState.PAYMENT_CONFLICT)
            return PaymentResult.RECONCILIATION_REQUIRED;
        if (state != PaymentState.RECEIVED
                && state != PaymentState.FAILED_RETRYABLE
                && state != PaymentState.UNKNOWN_ORDER) return PaymentResult.REJECTED;

        PaymentOrder order = orders.lockPaymentOrder(event.getOrderId());
        if (order == null || !order.getOrderId().equals(event.getOutTradeNo())) {
            if (payments.rechargeReference(event.getOrderId()) > 0)
                return reject(event, PaymentState.UNSUPPORTED_RECHARGE, PaymentResult.REJECTED);
            return reject(event, PaymentState.UNKNOWN_ORDER, PaymentResult.UNKNOWN_ORDER);
        }
        if (Boolean.TRUE.equals(order.getDeleted())
                || !Integer.valueOf(0).equals(order.getIsSystemDel())
                || !Integer.valueOf(0).equals(order.getRefundStatus())
                || (!Integer.valueOf(0).equals(order.getPaid())
                        && !Integer.valueOf(1).equals(order.getPaid())))
            return reject(
                    event,
                    PaymentState.RECONCILIATION_REQUIRED,
                    PaymentResult.RECONCILIATION_REQUIRED);
        long payable;
        try {
            payable = Money.cents(order.getPayPrice());
        } catch (ArithmeticException | IllegalArgumentException invalidAmount) {
            return reject(
                    event,
                    PaymentState.RECONCILIATION_REQUIRED,
                    PaymentResult.RECONCILIATION_REQUIRED);
        }
        if (payable != event.getAmountCents())
            return reject(event, PaymentState.PAYMENT_AMOUNT_MISMATCH, PaymentResult.REJECTED);
        if (Integer.valueOf(1).equals(order.getPaid())) {
            // Historical paid orders without our event are conflicts, never guessed duplicates.
            PaymentRecord first = payments.successful(order.getOrderId());
            if (first != null && first.getId().equals(eventId))
                return PaymentResult.IDEMPOTENT_DUPLICATE;
            return reject(
                    event, PaymentState.PAYMENT_CONFLICT, PaymentResult.RECONCILIATION_REQUIRED);
        }
        if (!Integer.valueOf(0).equals(order.getStatus()))
            return reject(
                    event,
                    PaymentState.RECONCILIATION_REQUIRED,
                    PaymentResult.RECONCILIATION_REQUIRED);
        String payType =
                switch (event.getProvider()) {
                    case "WECHAT" -> "weixin";
                    case "ALIPAY" -> "alipay";
                    case "BALANCE" -> "yue";
                    case "CASH" -> "cash";
                    default -> throw new IllegalArgumentException("INVALID_PROVIDER");
                };
        if (orders.markPaid(order.getId(), payType) != 1) {
            PaymentOrder current = orders.lockPaymentOrder(order.getOrderId());
            if (current == null)
                return reject(event, PaymentState.UNKNOWN_ORDER, PaymentResult.UNKNOWN_ORDER);
            if (Integer.valueOf(1).equals(current.getPaid())) {
                PaymentRecord first = payments.successful(current.getOrderId());
                if (first != null && first.getId().equals(eventId))
                    return PaymentResult.IDEMPOTENT_DUPLICATE;
                return reject(
                        event,
                        PaymentState.PAYMENT_CONFLICT,
                        PaymentResult.RECONCILIATION_REQUIRED);
            }
            return reject(
                    event,
                    PaymentState.RECONCILIATION_REQUIRED,
                    PaymentResult.RECONCILIATION_REQUIRED);
        }
        // All database effects and the SUCCESS unique constraint share the same transaction.
        effects.apply(order, payType);
        payments.state(eventId, PaymentState.SUCCESS.name(), null, order.getOrderId());
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            effects.afterCommit(order);
                        } catch (RuntimeException failure) {
                            log.warn(
                                    "payment notification failed eventId={} category={}",
                                    eventId,
                                    failure.getClass().getSimpleName());
                        }
                    }
                });
        return PaymentResult.FIRST_SUCCESS;
    }

    private PaymentResult reject(PaymentRecord event, PaymentState state, PaymentResult result) {
        payments.state(event.getId(), state.name(), state.name(), null);
        log.warn("payment review eventId={} status={}", event.getId(), state);
        return result;
    }
}
