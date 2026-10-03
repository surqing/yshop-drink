package co.yixiang.yshop.module.member.service.wallet;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WalletBalance {
    private Long id;
    private BigDecimal nowMoney;
    private Boolean deleted;
}
