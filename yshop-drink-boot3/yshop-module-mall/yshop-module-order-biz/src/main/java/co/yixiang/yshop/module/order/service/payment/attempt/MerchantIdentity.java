package co.yixiang.yshop.module.order.service.payment.attempt;

import lombok.Data;

/** Explicit projection: merchant secrets are not selected or decrypted. */
@Data
public class MerchantIdentity {
    private String detailsId;
    private String payType;
    private String appid;
    private String mchId;
    private String seller;
}
