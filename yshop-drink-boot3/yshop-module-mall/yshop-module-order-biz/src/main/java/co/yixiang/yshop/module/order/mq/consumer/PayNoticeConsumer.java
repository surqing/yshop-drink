package co.yixiang.yshop.module.order.mq.consumer;

import co.yixiang.yshop.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import co.yixiang.yshop.module.order.service.payment.PaymentFinalizationService;
import co.yixiang.yshop.module.pay.mq.message.PayNoticeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 消息队列处理支付消息
 */
@Component
@Slf4j
public class PayNoticeConsumer extends AbstractRedisStreamMessageListener<PayNoticeMessage> {

    @Resource
    private PaymentFinalizationService paymentFinalizationService;

    @Override
    public void onMessage(PayNoticeMessage message) {
        if (message.getEventId() == null || !message.getEventId().matches("[a-f0-9]{32}")) {
            // Legacy orderId/payType messages cannot prove signature or amount. Quarantine by safe log, no payment.
            log.warn("ignored unverified legacy payment message");
            return;
        }
        paymentFinalizationService.processPending(message.getEventId());

    }
}
