package co.yixiang.yshop.module.order.service.payment;

import lombok.Data;

import java.math.BigDecimal;

/** Deliberately excludes names, telephone, addresses and openid. */
@Data
public class PaymentOrder {
    private Long id;
    private String orderId;
    private Long uid;
    private BigDecimal payPrice;
    private Integer paid;
    private Integer status;
    private Integer refundStatus;
    private Integer isSystemDel;
    private Boolean deleted;
}
