package co.yixiang.yshop.module.order.controller.admin.storeorder.vo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class StoreOrderFulfillmentTransitionReqVO {
    @NotNull
    private Long id;

    @NotBlank
    @Pattern(regexp = "ACCEPT|START_PREPARING|MARK_READY|COLLECT")
    private String action;

    @NotNull
    @Min(1)
    private Integer expectedVersion;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    private String requestKey;
}
