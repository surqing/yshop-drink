package co.yixiang.yshop.module.order.service.ordering;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Database-backed recovery for Phase 6A orders; safe with Redis unavailable and multiple workers.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "yshop.order.expiry-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class UnpaidOrderExpiry {
    private final OrderPlacementService orders;

    @Scheduled(fixedDelayString = "${yshop.order.expiry-delay-millis:60000}")
    public void expire() {
        orders.expireBatch();
    }
}
