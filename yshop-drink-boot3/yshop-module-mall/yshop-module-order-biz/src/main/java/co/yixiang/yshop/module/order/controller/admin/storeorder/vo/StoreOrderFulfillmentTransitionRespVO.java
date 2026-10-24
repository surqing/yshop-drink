package co.yixiang.yshop.module.order.controller.admin.storeorder.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StoreOrderFulfillmentTransitionRespVO {
    private String fulfillmentStatus;
    private Integer fulfillmentVersion;
    private Boolean replayed;
}
