package co.yixiang.yshop.module.order.service.payment.attempt;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Set;

/** Read-only cancellation admission. Caller must already hold the order row lock. */
@Service
@RequiredArgsConstructor
public class PaymentCancellationGuard {
    private final JdbcTemplate jdbc;

    public void assertSafeAfterOrderLock(String orderId) {
        // Current committed receipt reads must not reuse an earlier REPEATABLE READ snapshot.
        // Never lock payment events here: the processor's order is event -> order -> attempt.
        // READ COMMITTED avoids that inversion and needs no second pooled connection.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                        .equals(
                                TransactionSynchronizationManager
                                        .getCurrentTransactionIsolationLevel()))
            throw new IllegalStateException("CANCELLATION_REQUIRES_READ_COMMITTED");
        var attempts =
                jdbc.queryForList(
                        "SELECT"
                            + " attempt_id,status,provider,prepay_requested_at,prepay_reference,provider_transaction_id,payment_event_id,paid_at,reconciliation_token,reconciliation_lease_until,remote_terminal_state,remote_confirmed_at"
                            + " FROM yshop_order_payment_attempt WHERE order_id=? ORDER BY"
                            + " attempt_id FOR UPDATE",
                        orderId);
        for (var a : attempts) {
            String state = String.valueOf(a.get("status"));
            if (!Set.of("WECHAT", "ALIPAY").contains(String.valueOf(a.get("provider")))
                    || !Set.of("CANCELED", "EXPIRED", "FAILED").contains(state))
                throw reject("PAYMENT_ATTEMPT_PREVENTS_CANCELLATION");
            if (present(a, "provider_transaction_id")
                    || present(a, "payment_event_id")
                    || present(a, "paid_at")
                    || present(a, "reconciliation_token")
                    || present(a, "reconciliation_lease_until"))
                throw reject("PAYMENT_EVIDENCE_PREVENTS_CANCELLATION");
            boolean requested = present(a, "prepay_requested_at") || present(a, "prepay_reference");
            String remote = String.valueOf(a.get("remote_terminal_state"));
            boolean remoteSafe =
                    "WECHAT".equals(a.get("provider"))
                            && present(a, "remote_confirmed_at")
                            && (("CANCELED".equals(state)
                                            && Set.of("CLOSED", "REVOKED").contains(remote))
                                    || ("FAILED".equals(state) && "PAYERROR".equals(remote)));
            boolean neverRequested =
                    !requested
                            && !present(a, "remote_terminal_state")
                            && !present(a, "remote_confirmed_at");
            if (!neverRequested && !remoteSafe) throw reject("REMOTE_TERMINATION_NOT_CONFIRMED");
        }
        // Any recorded success assertion/conflict is financial evidence, even if rejected or
        // awaiting processing. Keep it for reconciliation; cancellation never clears markers.
        if (jdbc.queryForObject(
                                "SELECT COUNT(*) FROM yshop_order_payment WHERE order_id=?",
                                Long.class,
                                orderId)
                        > 0
                || jdbc.queryForObject(
                                "SELECT COUNT(*) FROM yshop_order_payment_conflict WHERE"
                                        + " claimed_order_id=?",
                                Long.class,
                                orderId)
                        > 0) throw reject("PAYMENT_REVIEW_PREVENTS_CANCELLATION");
    }

    private static RuntimeException reject(String code) {
        return co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception(
                new co.yixiang.yshop.framework.common.exception.ErrorCode(1008003091, code));
    }

    private static boolean present(Map<String, Object> row, String field) {
        return row.get(field) != null;
    }
}
