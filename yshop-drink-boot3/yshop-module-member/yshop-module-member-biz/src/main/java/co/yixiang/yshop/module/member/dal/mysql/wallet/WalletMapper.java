package co.yixiang.yshop.module.member.dal.mysql.wallet;

import co.yixiang.yshop.module.member.service.wallet.*;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface WalletMapper {
    @Select("SELECT id,now_money,deleted FROM yshop_user WHERE id=#{uid} FOR UPDATE")
    WalletBalance lockUser(Long uid);

    @Select("SELECT * FROM yshop_member_wallet_transaction WHERE idempotency_key=#{key}")
    WalletTransaction find(String key);

    @Select(
            "SELECT * FROM yshop_member_wallet_transaction WHERE uid=#{uid} ORDER BY create_time,id"
                + " FOR UPDATE")
    List<WalletTransaction> currentTransactions(Long uid);

    @Select(
            "SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END),0) FROM"
                + " yshop_member_wallet_transaction WHERE uid=#{uid}")
    BigDecimal sum(Long uid);

    @Update(
            "UPDATE yshop_user SET now_money=#{after},update_time=CURRENT_TIMESTAMP WHERE id=#{uid}"
                + " AND now_money=#{before} AND deleted=0")
    int change(
            @Param("uid") Long uid,
            @Param("before") BigDecimal before,
            @Param("after") BigDecimal after);

    @Insert(
            "INSERT INTO"
                + " yshop_member_wallet_transaction(id,uid,direction,type,amount,balance_before,balance_after,business_id,idempotency_key)"
                + " VALUES(#{id},#{uid},#{direction},#{type},#{amount},#{balanceBefore},#{balanceAfter},#{businessId},#{idempotencyKey})")
    int insert(WalletTransaction row);

    @Select("SELECT id FROM yshop_user ORDER BY id")
    List<Long> userIds();

    @Select("SELECT COUNT(*) FROM yshop_user WHERE now_money<0")
    int negativeCount();
}
