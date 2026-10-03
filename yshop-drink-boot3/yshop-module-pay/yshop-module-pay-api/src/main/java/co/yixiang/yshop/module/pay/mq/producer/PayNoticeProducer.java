package co.yixiang.yshop.module.pay.mq.producer;

import co.yixiang.yshop.framework.mq.redis.core.RedisMQTemplate;
import co.yixiang.yshop.module.pay.mq.message.PayNoticeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Slf4j
@Component
public class PayNoticeProducer {
    @Resource
    private RedisMQTemplate redisMQTemplate;

    /**
     * 发送消息
     * @param eventId 已提交的支付事件编号
     */
    public void sendPayNoticeMessage(String eventId) {
        PayNoticeMessage payNoticeMessage = new PayNoticeMessage().setEventId(eventId);
        redisMQTemplate.send(payNoticeMessage);
    }
}
