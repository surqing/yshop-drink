package co.yixiang.yshop.module.order.dal.mysql.payment;

import co.yixiang.yshop.module.order.service.payment.attempt.PaymentAttempt;

import org.apache.ibatis.annotations.*;

@Mapper
public interface PaymentAttemptMapper {
    @Select("SELECT * FROM yshop_order_payment_attempt WHERE attempt_id=#{id}")
    PaymentAttempt find(String id);

    @Select("SELECT * FROM yshop_order_payment_attempt WHERE provider_order_reference=#{ref}")
    PaymentAttempt reference(String ref);

    @Select("SELECT * FROM yshop_order_payment_attempt WHERE attempt_id=#{id} FOR UPDATE")
    PaymentAttempt lock(String id);

    @Select(
            "SELECT * FROM yshop_order_payment_attempt WHERE order_id=#{order} AND"
                    + " idempotency_key=#{key} FOR UPDATE")
    PaymentAttempt key(@Param("order") String order, @Param("key") String key);

    @Select("SELECT * FROM yshop_order_payment_attempt WHERE active_order_id=#{order} FOR UPDATE")
    PaymentAttempt active(String order);

    @Insert(
            "INSERT INTO"
                + " yshop_order_payment_attempt(attempt_id,order_id,uid,idempotency_key,provider,merchant_details_id,amount_cents,currency,appid,merchant_identity,provider_order_reference,status)"
                + " VALUES(#{attemptId},#{orderId},#{uid},#{idempotencyKey},#{provider},#{merchantDetailsId},#{amountCents},'CNY',#{appid},#{merchantIdentity},#{providerOrderReference},'CREATED')")
    int insert(PaymentAttempt attempt);

    @Update(
            "UPDATE yshop_order_payment_attempt SET"
                + " prepay_reference=#{ref},status='PREPAY_CREATED',update_time=CURRENT_TIMESTAMP(6)"
                + " WHERE attempt_id=#{id} AND status='CREATED' AND prepay_reference IS NULL")
    int prepay(@Param("id") String id, @Param("ref") String ref);

    @Update(
            "UPDATE yshop_order_payment_attempt SET"
                + " prepay_requested_at=CURRENT_TIMESTAMP(6),update_time=CURRENT_TIMESTAMP(6) WHERE"
                + " attempt_id=#{id} AND status='CREATED' AND prepay_requested_at IS NULL")
    int claimPrepay(String id);

    @Select(
            "SELECT * FROM yshop_order_payment_attempt WHERE order_id=#{orderId} AND"
                    + " provider='WECHAT' ORDER BY create_time LIMIT 1 FOR UPDATE")
    PaymentAttempt wechatHistory(String orderId);

    @Update(
            "UPDATE yshop_order_payment_attempt SET"
                + " status=#{state},update_time=CURRENT_TIMESTAMP(6) WHERE attempt_id=#{id} AND"
                + " status='CREATED' AND prepay_reference IS NULL AND prepay_requested_at IS NULL")
    int terminate(@Param("id") String id, @Param("state") String state);

    @Update(
            "UPDATE yshop_order_payment_attempt SET"
                + " status='PAID',provider_transaction_id=#{transaction},payment_event_id=#{event},paid_at=CURRENT_TIMESTAMP(6),update_time=CURRENT_TIMESTAMP(6)"
                + " WHERE attempt_id=#{id} AND status IN ('CREATED','PREPAY_CREATED') AND"
                + " provider_transaction_id IS NULL AND payment_event_id IS NULL")
    int paid(
            @Param("id") String id,
            @Param("transaction") String transaction,
            @Param("event") String event);

    @Update(
            "UPDATE yshop_order_payment_attempt SET"
                + " reconciliation_token=#{token},reconciliation_lease_until=TIMESTAMPADD(SECOND,#{lease},CURRENT_TIMESTAMP(6))"
                + " WHERE attempt_id=#{id} AND provider='WECHAT' AND status IN"
                + " ('CREATED','PREPAY_CREATED') AND (prepay_requested_at IS NOT NULL OR"
                + " prepay_reference IS NOT NULL) AND"
                + " TIMESTAMPDIFF(SECOND,COALESCE(prepay_requested_at,create_time),CURRENT_TIMESTAMP(6))>=#{age}"
                + " AND (reconciliation_lease_until IS NULL OR"
                + " reconciliation_lease_until<=CURRENT_TIMESTAMP(6))")
    int claimReconciliation(
            @Param("id") String id,
            @Param("token") String token,
            @Param("lease") int lease,
            @Param("age") int age);

    @Update(
            "UPDATE yshop_order_payment_attempt SET"
                + " reconciliation_token=NULL,reconciliation_lease_until=NULL WHERE"
                + " attempt_id=#{id} AND reconciliation_token=#{token}")
    int releaseReconciliation(@Param("id") String id, @Param("token") String token);

    @Update(
            "UPDATE yshop_order_payment_attempt SET"
                + " status=#{status},remote_terminal_state=#{remote},remote_confirmed_at=CURRENT_TIMESTAMP(6),reconciliation_token=NULL,reconciliation_lease_until=NULL,update_time=CURRENT_TIMESTAMP(6)"
                + " WHERE attempt_id=#{id} AND status IN ('CREATED','PREPAY_CREATED') AND"
                + " reconciliation_token=#{token} AND"
                + " reconciliation_lease_until>CURRENT_TIMESTAMP(6)")
    int remoteTerminal(
            @Param("id") String id,
            @Param("token") String token,
            @Param("status") String status,
            @Param("remote") String remote);

    @Select(
            "SELECT attempt_id FROM yshop_order_payment_attempt WHERE provider='WECHAT' AND status"
                + " IN ('CREATED','PREPAY_CREATED') AND (prepay_requested_at IS NOT NULL OR"
                + " prepay_reference IS NOT NULL) AND"
                + " TIMESTAMPDIFF(SECOND,COALESCE(prepay_requested_at,create_time),CURRENT_TIMESTAMP(6))>=#{age}"
                + " AND (reconciliation_lease_until IS NULL OR"
                + " reconciliation_lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY create_time LIMIT"
                + " #{limit}")
    java.util.List<String> reconciliationCandidates(
            @Param("age") int age, @Param("limit") int limit);

    @Select(
            "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE attempt_id=#{id} AND"
                + " reconciliation_token=#{token} AND"
                + " reconciliation_lease_until>CURRENT_TIMESTAMP(6)")
    int leaseCurrent(@Param("id") String id, @Param("token") String token);

    @Select(
            "SELECT * FROM yshop_order_payment_attempt WHERE order_id=#{orderId} ORDER BY"
                + " create_time DESC,attempt_id DESC LIMIT 1 FOR UPDATE")
    PaymentAttempt latest(String orderId);
}
