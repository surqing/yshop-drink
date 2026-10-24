package co.yixiang.yshop.module.score.controller.admin.scoreorder.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 积分商城订单物流更新 Request VO")
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScoreOrderUpdateReqVO {

    @Schema(description = "订单 id", required = true, example = "19735")
    @NotNull(message = "id不能为空")
    private Long id;

    @Schema(description = "快递公司编码", required = true)
    @NotBlank(message = "快递公司编码不能为空")
    private String expressSn;

    @Schema(description = "快递单号", required = true)
    @NotBlank(message = "快递单号不能为空")
    private String expressNumber;

    @Schema(description = "快递公司", required = true)
    @NotBlank(message = "快递公司不能为空")
    private String expressCompany;
}
