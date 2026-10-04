package co.yixiang.yshop.module.order.dal.mysql.payment;

import co.yixiang.yshop.module.order.service.payment.attempt.MerchantIdentity;

import org.apache.ibatis.annotations.*;

@Mapper
public interface PaymentMerchantIdentityMapper {
    @Select(
            "SELECT details_id,pay_type,appid,mch_id,seller FROM merchant_details WHERE"
                + " details_id=#{id} AND deleted=0")
    MerchantIdentity find(String id);
}
