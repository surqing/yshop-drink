package co.yixiang.yshop.module.coupon.service.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static co.yixiang.yshop.module.coupon.service.marketing.CouponPolicy.*;

/** Called only inside existing order transactions after their order lock/creation admission. */
public final class CouponLifecycle {
    private final JdbcTemplate jdbc;
    public CouponLifecycle(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("COUPON_TRANSACTION_REQUIRED");
    }
    public void audit(String key, long coupon, Long instance, String order, Long actor,
                      String actorType, String kind, String reason) {
        transaction();
        jdbc.update("INSERT INTO yshop_coupon_operation(event_key,coupon_id,coupon_user_id,order_id,actor_id,actor_type,kind,reason) VALUES(?,?,?,?,?,?,?,?)",
                key, coupon, instance, order, actor, actorType, kind, reason);
    }
    private Map<String,Object> locked(long instance, long uid) {
        transaction();
        var rows = jdbc.queryForList("SELECT *, (type+0) AS coupon_type FROM yshop_coupon_user WHERE id=? AND user_id=? AND deleted=0 FOR UPDATE", instance, uid);
        if (rows.size() != 1) throw reject("COUPON_NOT_AVAILABLE");
        var row = rows.get(0); row.put("type", row.get("coupon_type")); return row;
    }
    public BigDecimal reserve(long instance, long uid, long shop, int type, BigDecimal subtotal,
                              LocalDateTime now, String order) {
        var row = locked(instance, uid);
        BigDecimal discount = discount(row, uid, shop, type, subtotal, now);
        if (jdbc.update("UPDATE yshop_coupon_user SET status=1,reserved_order_id=? WHERE id=? AND user_id=? AND status=0 AND reserved_order_id IS NULL AND invalid_reason IS NULL AND redeemed_at IS NULL", order, instance, uid) != 1)
            throw reject("COUPON_NOT_AVAILABLE");
        audit("RESERVE:"+order, number(row,"coupon_id"), instance, order, uid, "MEMBER", "RESERVE", "订单创建预占，尚未付款");
        return discount;
    }
    public void release(long instance, long uid, String order) {
        var row = locked(instance, uid);
        if (number(row,"status") != 1 || !order.equals(row.get("reserved_order_id"))
                || row.get("redeemed_at") != null || row.get("redeemed_order_id") != null)
            throw reject("ORDER_COUPON_RESTORE_FAILED");
        if (jdbc.update("UPDATE yshop_coupon_user SET status=0,reserved_order_id=NULL WHERE id=? AND user_id=? AND status=1 AND reserved_order_id=? AND redeemed_at IS NULL", instance, uid, order) != 1)
            throw reject("ORDER_COUPON_RESTORE_FAILED");
        audit("RELEASE:"+order, number(row,"coupon_id"), instance, order, uid, "SYSTEM", "RELEASE", "通过支付取消防护后解除预占；不延长有效期");
    }
    /** The trusted PaymentProcessor invokes effects in the same paid/SUCCESS transaction. */
    public void redeem(String order, long uid) {
        transaction();
        var orders = jdbc.queryForList("SELECT coupon_id,coupon_price,total_price,ordering_version,paid FROM yshop_store_order WHERE order_id=? AND uid=?", order, uid);
        if (orders.size()!=1) throw reject("COUPON_PAYMENT_ORDER_MISSING");
        var o=orders.get(0);
        if (number(o,"coupon_id")==0) return;
        // Old order reservations were not established by Phase 6A. Do not invent or rewrite proof.
        if (number(o,"ordering_version")!=1) return;
        if (number(o,"paid")!=1) throw reject("COUPON_PAYMENT_NOT_COMMITTED");
        long id=number(o,"coupon_id"); var row=locked(id,uid);
        if (number(row,"status")!=1 || !order.equals(row.get("reserved_order_id"))
                || row.get("invalid_reason")!=null || row.get("redeemed_at")!=null
                || amount(row.get("value")).min(amount(o.get("total_price"))).compareTo(amount(o.get("coupon_price")))!=0)
            throw reject("COUPON_REDEMPTION_CONFLICT");
        if (jdbc.update("UPDATE yshop_coupon_user SET redeemed_order_id=?,redeemed_at=CURRENT_TIMESTAMP WHERE id=? AND status=1 AND reserved_order_id=? AND redeemed_at IS NULL",order,id,order)!=1)
            throw reject("COUPON_REDEMPTION_CONFLICT");
        audit("REDEEM:"+order,number(row,"coupon_id"),id,order,uid,"SYSTEM","REDEEM","可信付款事务核销");
    }
    public String state(Map<String,Object> row, LocalDateTime now) {
        String order=Objects.toString(row.get("reserved_order_id"),Objects.toString(row.get("redeemed_order_id"),""));
        boolean paid=false,pending=false;
        if (!order.isEmpty()) {
            var found=jdbc.queryForList("SELECT paid,deleted,coupon_id FROM yshop_store_order WHERE order_id=? AND uid=? AND coupon_id=?",order,row.get("user_id"),row.get("id"));
            if (found.size()==1) {
                var o=found.get(0);
                pending=number(o,"paid")==0 && number(o,"deleted")==0;
                paid=number(o,"paid")==1 && jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_payment WHERE order_id=? AND status='SUCCESS'",Long.class,order)>0;
            }
        }
        return CouponPolicy.state(row,now,paid,pending);
    }
}
