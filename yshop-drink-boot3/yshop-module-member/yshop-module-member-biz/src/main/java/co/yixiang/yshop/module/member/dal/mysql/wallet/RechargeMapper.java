package co.yixiang.yshop.module.member.dal.mysql.wallet;

import co.yixiang.yshop.module.member.service.wallet.RechargeOrder;

import org.apache.ibatis.annotations.*;

@Mapper
public interface RechargeMapper {
    @Insert(
            "INSERT INTO"
                + " yshop_member_recharge_order(recharge_no,uid,pay_amount,bonus_amount,credit_amount,status,provider)"
                + " VALUES(#{rechargeNo},#{uid},#{payAmount},#{bonusAmount},#{creditAmount},'CREATED','SYNTHETIC')")
    int insert(RechargeOrder row);

    @Select("SELECT * FROM yshop_member_recharge_order WHERE recharge_no=#{id} FOR UPDATE")
    RechargeOrder lock(String id);

    @Update(
            "UPDATE yshop_member_recharge_order SET"
                + " status='SUCCESS',provider_transaction_id=#{transaction},paid_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP"
                + " WHERE recharge_no=#{id} AND status='CREATED'")
    int complete(@Param("id") String id, @Param("transaction") String transaction);
}
