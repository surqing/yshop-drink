package co.yixiang.yshop.module.member.service.wallet;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Append-only monetary evidence; no update/delete mapper exists. */
@Data
public class WalletTransaction {
    private String id;
    private Long uid;
    private String direction;
    private String type;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String businessId;
    private String idempotencyKey;
    private LocalDateTime createTime;
}
