package co.yixiang.yshop.module.order.dal.mysql.payment;

import co.yixiang.yshop.module.order.service.payment.PaymentRecord;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** Explicit SQL includes canceled/deleted records for reconciliation; no logical-delete filter. */
@Mapper
public interface PaymentMapper {
    @Insert(
            "INSERT INTO"
                + " yshop_order_payment(id,order_id,provider,merchant_details_id,out_trade_no,provider_transaction_id,amount_cents,appid,mch_id,result_code,status,received_at,last_seen_at)"
                + " VALUES(#{id},#{orderId},#{provider},#{merchantDetailsId},#{outTradeNo},"
                + "#{providerTransactionId},#{amountCents},#{appid},#{mchId},#{resultCode},'RECEIVED',#{receivedAt},#{receivedAt})"
                + " ON DUPLICATE KEY UPDATE id=id")
    int insertOrLock(PaymentRecord record);

    @Select(
            "SELECT * FROM yshop_order_payment WHERE provider=#{provider} AND"
                    + " provider_transaction_id=#{transaction} FOR UPDATE")
    PaymentRecord lockTransaction(
            @Param("provider") String provider, @Param("transaction") String transaction);

    @Select("SELECT * FROM yshop_order_payment WHERE id=#{id} FOR UPDATE")
    PaymentRecord lockEvent(String id);

    @Select("SELECT * FROM yshop_order_payment WHERE success_order_id=#{orderId}")
    PaymentRecord successful(String orderId);

    @Update(
            "UPDATE yshop_order_payment SET"
                    + " duplicate_count=duplicate_count+1,last_seen_at=CURRENT_TIMESTAMP WHERE"
                    + " id=#{id}")
    int seen(String id);

    @Update(
            "UPDATE yshop_order_payment SET"
                + " status=#{status},failure_reason=#{reason},success_order_id=#{successOrderId},processed_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP"
                + " WHERE id=#{id}")
    int state(
            @Param("id") String id,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("successOrderId") String successOrderId);

    @Update(
            "UPDATE yshop_order_payment SET"
                + " status='FAILED_RETRYABLE',failure_reason='DATABASE_RETRY',update_time=CURRENT_TIMESTAMP"
                + " WHERE id=#{id} AND status IN ('RECEIVED','FAILED_RETRYABLE')")
    int failed(String id);

    @Insert(
            "INSERT INTO"
                + " yshop_order_payment_conflict(id,event_id,claimed_order_id,reason,received_at) "
                + "VALUES(#{id},#{eventId},#{orderId},#{reason},CURRENT_TIMESTAMP)")
    int conflict(
            @Param("id") String id,
            @Param("eventId") String eventId,
            @Param("orderId") String orderId,
            @Param("reason") String reason);

    @Select(
            "SELECT id FROM yshop_order_payment WHERE provider IN ('WECHAT','ALIPAY') AND status IN"
                    + " ('RECEIVED','FAILED_RETRYABLE') ORDER BY update_time LIMIT 100")
    List<String> pending();

    @Select("SELECT COUNT(*) FROM yshop_user_bill WHERE extend_field=#{reference}")
    int rechargeReference(String reference);
}
