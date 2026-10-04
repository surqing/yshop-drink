package co.yixiang.yshop.module.member.service.wallet;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RechargeOrder {
    private String rechargeNo;
    private Long uid;
    private BigDecimal payAmount;
    private BigDecimal bonusAmount;
    private BigDecimal creditAmount;
    private String status;
    private String provider;
    private String providerTransactionId;
    private LocalDateTime paidAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
