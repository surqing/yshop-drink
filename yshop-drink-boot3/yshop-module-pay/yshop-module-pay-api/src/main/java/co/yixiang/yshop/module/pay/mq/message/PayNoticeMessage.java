package co.yixiang.yshop.module.pay.mq.message;

import co.yixiang.yshop.framework.mq.redis.core.stream.AbstractRedisStreamMessage;
import lombok.Data;

/** Wake-up reference to an already committed, verified database event. */
@Data
public class PayNoticeMessage extends AbstractRedisStreamMessage {
    private String eventId;
    @Override public String getStreamKey() { return "order.pay.notice"; }
}
