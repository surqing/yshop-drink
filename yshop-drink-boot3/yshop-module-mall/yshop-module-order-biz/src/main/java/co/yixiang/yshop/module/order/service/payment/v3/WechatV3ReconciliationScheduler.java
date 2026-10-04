package co.yixiang.yshop.module.order.service.payment.v3;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(name = "yshop.pay.wechat-v3.reconciliation-enabled", havingValue = "true")
public class WechatV3ReconciliationScheduler {
    private final WechatV3ReconciliationService recovery;
    private final boolean closeUnpaid;
    private final int batch;

    public WechatV3ReconciliationScheduler(
            WechatV3ReconciliationService recovery,
            @Value("${yshop.pay.wechat-v3.reconciliation-close-unpaid:false}") boolean closeUnpaid,
            @Value("${yshop.pay.wechat-v3.reconciliation-batch:25}") int batch) {
        this.recovery = recovery;
        this.closeUnpaid = closeUnpaid;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${yshop.pay.wechat-v3.reconciliation-delay-ms:60000}")
    public void recover() {
        try {
            for (String id : recovery.candidates(batch)) recovery.reconcile(id, closeUnpaid);
        } catch (RuntimeException ignored) {
            log.warn("WeChat recovery deferred; database unavailable");
        }
    }
}
