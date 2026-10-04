package co.yixiang.yshop.module.order.service.payment;

import co.yixiang.yshop.module.order.dal.mysql.payment.PaymentMapper;
import co.yixiang.yshop.module.pay.callback.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentInbox {
    private final PaymentMapper mapper;

    public PaymentInbox(PaymentMapper mapper) {
        this.mapper = mapper;
    }

    public record Receipt(String id, PaymentResult conflict) {}

    /** Separate commit survives business rollback and Redis failure. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Receipt capture(PaymentSuccessEvent event) {
        return record(event);
    }

    /** BALANCE receipt joins the wallet debit/finalization transaction, using one connection. */
    @Transactional(rollbackFor=Exception.class)
    public Receipt captureBalance(PaymentSuccessEvent event) {
        if(event.provider()!=PaymentSuccessEvent.Provider.BALANCE) throw new IllegalArgumentException("BALANCE_PROVIDER_REQUIRED");
        return record(event);
    }

    private Receipt record(PaymentSuccessEvent event) {
        PaymentRecord row = new PaymentRecord();
        row.setId(UUID.randomUUID().toString().replace("-", ""));
        row.setOrderId(event.outTradeNo());
        row.setOutTradeNo(event.outTradeNo());
        row.setProvider(event.provider().name());
        row.setProviderTransactionId(event.providerTransactionId());
        row.setMerchantDetailsId(event.merchantDetailsId());
        row.setAmountCents(event.totalFeeCents());
        row.setAppid(event.appid());
        row.setMchId(event.mchId());
        row.setResultCode(event.resultCode());
        row.setReceivedAt(event.receivedAt());
        // A duplicate INSERT takes an InnoDB shared lock. Catching it and then requesting
        // FOR UPDATE deadlocks simultaneous duplicates during the shared-to-exclusive upgrade.
        // The no-op upsert instead acquires the exclusive lock without changing the old payload.
        // JDBC affected-row conventions differ, so identify a new receipt by its generated ID.
        mapper.insertOrLock(row);
        PaymentRecord existing =
                mapper.lockTransaction(row.getProvider(), row.getProviderTransactionId());
        if (existing == null) throw new IllegalStateException("PAYMENT_RECORD_NOT_FOUND");
        if (row.getId().equals(existing.getId())) return new Receipt(row.getId(), null);
        mapper.seen(existing.getId());
        boolean sameOrder = existing.getOrderId().equals(event.outTradeNo());
        if (!sameOrder
                || existing.getAmountCents() != event.totalFeeCents()
                || !Objects.equals(existing.getMerchantDetailsId(), event.merchantDetailsId())
                || !Objects.equals(existing.getAppid(), event.appid())
                || !Objects.equals(existing.getMchId(), event.mchId())) {
            mapper.conflict(
                    row.getId(),
                    existing.getId(),
                    event.outTradeNo(),
                    sameOrder ? "PAYMENT_CONFLICT" : "TRANSACTION_ORDER_CONFLICT");
            return new Receipt(existing.getId(), PaymentResult.REJECTED);
        }
        return new Receipt(existing.getId(), null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String eventId) {
        mapper.failed(eventId);
    }
}
