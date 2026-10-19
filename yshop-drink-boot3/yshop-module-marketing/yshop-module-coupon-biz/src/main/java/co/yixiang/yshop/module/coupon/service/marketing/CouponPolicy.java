package co.yixiang.yshop.module.coupon.service.marketing;

import co.yixiang.yshop.framework.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;

/** Shared rights interpretation. An ambiguous historical status is never payment proof. */
public final class CouponPolicy {
    private CouponPolicy() {}
    public static RuntimeException reject(String reason) {
        return exception(new ErrorCode(1006002090, reason));
    }
    public static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        if (value == null) return 0;
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException error) { throw reject("COUPON_REVIEW_REQUIRED"); }
    }
    public static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof Timestamp stamp) return stamp.toLocalDateTime();
        throw reject("COUPON_REVIEW_REQUIRED");
    }
    public static BigDecimal amount(Object value) {
        try {
            if (value == null) throw reject("COUPON_INVALID_AMOUNT");
            BigDecimal result = new BigDecimal(value.toString()).setScale(2, RoundingMode.UNNECESSARY);
            if (result.signum() < 0 || result.compareTo(new BigDecimal("999999.99")) > 0)
                throw reject("COUPON_INVALID_AMOUNT");
            return result;
        } catch (ArithmeticException | NumberFormatException error) { throw reject("COUPON_INVALID_AMOUNT"); }
    }
    public static SortedSet<Long> shops(String scope) {
        if (scope == null || !scope.matches("(?:0|[1-9][0-9]{0,17}(?:,[1-9][0-9]{0,17}){0,99})"))
            throw reject("COUPON_INVALID_STORE_SCOPE");
        var result = new TreeSet<Long>();
        for (String id : scope.split(",")) if (!result.add(Long.parseLong(id)))
            throw reject("COUPON_INVALID_STORE_SCOPE");
        return result;
    }
    public static boolean applies(String scope, long shop) {
        var ids = shops(scope);
        return ids.contains(0L) || ids.contains(shop);
    }
    public static String state(Map<String, Object> row, LocalDateTime now, boolean paidProof, boolean pendingProof) {
        if (number(row, "deleted") != 0 || row.get("invalid_reason") != null) return "INVALID";
        try {
            shops(Objects.toString(row.get("shop_id"), ""));
            if (amount(row.get("value")).signum() <= 0) return "REVIEW_REQUIRED";
            amount(row.get("least"));
            if (number(row, "type") < 0 || number(row, "type") > 2) return "REVIEW_REQUIRED";
            LocalDateTime start = time(row.get("start_time")), end = time(row.get("end_time"));
            if (!end.isAfter(start)) return "REVIEW_REQUIRED";
            if (number(row, "status") == 1) {
                if (row.get("redeemed_at") != null && row.get("redeemed_order_id") != null && paidProof)
                    return "USED";
                if (row.get("reserved_order_id") != null && paidProof) return "USED";
                if (row.get("reserved_order_id") != null && pendingProof) return "RESERVED";
                return "REVIEW_REQUIRED";
            }
            if (number(row, "status") != 0 || row.get("reserved_order_id") != null
                    || row.get("redeemed_at") != null || row.get("redeemed_order_id") != null)
                return "REVIEW_REQUIRED";
            if (!end.isAfter(now)) return "EXPIRED";
            if (start.isAfter(now)) return "NOT_YET_VALID";
            return "AVAILABLE";
        } catch (RuntimeException malformed) { return "REVIEW_REQUIRED"; }
    }
    public static BigDecimal discount(Map<String, Object> row, long uid, long shop, int type,
                                      BigDecimal subtotal, LocalDateTime now) {
        if (number(row, "user_id") != uid || !"AVAILABLE".equals(state(row, now, false, false))
                || !applies(Objects.toString(row.get("shop_id"), ""), shop)
                || (number(row, "type") != 0 && number(row, "type") != type)
                || subtotal.compareTo(amount(row.get("least"))) < 0)
            throw reject("COUPON_NOT_AVAILABLE");
        return amount(row.get("value")).min(subtotal);
    }
}
