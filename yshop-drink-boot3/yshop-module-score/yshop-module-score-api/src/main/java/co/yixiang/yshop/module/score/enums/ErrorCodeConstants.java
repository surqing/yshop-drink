package co.yixiang.yshop.module.score.enums;

import co.yixiang.yshop.framework.common.exception.ErrorCode;
public interface ErrorCodeConstants {
    ErrorCode SUBMISSION_KEY_INVALID = new ErrorCode(1008018004, "请更新小程序后重试兑换");
    ErrorCode SUBMISSION_CONFLICT = new ErrorCode(1008018005, "本次兑换内容已变更，请先确认原兑换结果");
    ErrorCode ORDER_PARAM_INVALID = new ErrorCode(1008018003, "兑换数量或商品参数有误");
    ErrorCode ORDER_NOT_EXISTS = new ErrorCode(1008018000, "积分商城订单不存在");
    ErrorCode PRODUCT_NOT_EXISTS = new ErrorCode(1008018001, "积分产品不存在");
    ErrorCode SCORE_NOT = new ErrorCode(1008018002, "积分不足");
    ErrorCode PRODUCT_NOT_STOCK = new ErrorCode(1008018002, "库存不足");
}

