package co.yixiang.yshop.module.pay.controller.admin.merchantdetails.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import jakarta.validation.constraints.Size;

/** Write-only credential inputs. Blank/absent values preserve existing credentials. */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MerchantDetailsWriteVO extends MerchantDetailsBaseVO {

    @Schema(description = "仅用于显式替换；留空保持原值", accessMode = Schema.AccessMode.WRITE_ONLY)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Size(max = 65536, message = "支付凭据长度超出限制")
    private String keyPrivate;

    @Schema(description = "仅用于显式替换；留空保持原值", accessMode = Schema.AccessMode.WRITE_ONLY)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Size(max = 65536, message = "支付凭据长度超出限制")
    private String keyCert;

    @Schema(description = "仅用于显式替换；留空保持原值", accessMode = Schema.AccessMode.WRITE_ONLY)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Size(max = 65536, message = "支付凭据长度超出限制")
    private String keyCertPwd;
}
