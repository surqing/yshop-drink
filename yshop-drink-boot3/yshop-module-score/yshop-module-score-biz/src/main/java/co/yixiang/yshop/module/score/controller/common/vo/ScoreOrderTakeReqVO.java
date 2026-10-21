package co.yixiang.yshop.module.score.controller.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "积分订单确认收货 Request VO")
public class ScoreOrderTakeReqVO {

    @NotNull(message = "订单 id 不能为空")
    @Schema(description = "订单 id", required = true, example = "19735")
    private Long id;
}
