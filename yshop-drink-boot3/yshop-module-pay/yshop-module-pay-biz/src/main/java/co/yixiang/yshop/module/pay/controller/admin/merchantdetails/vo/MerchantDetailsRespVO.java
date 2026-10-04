package co.yixiang.yshop.module.pay.controller.admin.merchantdetails.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Independent read model: credential material has no response property. */
@Data
@Schema(description = "管理后台 - 支付服务商配置（不返回凭据材料）")
public class MerchantDetailsRespVO {
    private String detailsId;
    private String payType;
    private String appid;
    private String mchId;
    private String certStoreType;
    @lombok.ToString.Exclude
    private String keyPublic;
    private String notifyUrl;
    private String returnUrl;
    private String signType;
    private String seller;
    private String subAppId;
    private String subMchId;
    private String inputCharset;
    private Integer isTest;
    @Schema(description = "私钥或 API 密钥已配置")
    private boolean privateKeyConfigured;
    @Schema(description = "证书密码已配置")
    private boolean certificatePasswordConfigured;
    @Schema(description = "附加证书已配置")
    private boolean keyCertificateConfigured;
    private String wechatApiVersion;
    private String merchantCertificateSerial;
    private String platformPublicKeyId;
    private boolean apiV3KeyConfigured;
}
