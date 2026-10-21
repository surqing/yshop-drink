package co.yixiang.yshop.module.order.service.fulfillment;

import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.framework.common.exception.ServiceException;
import co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO;
import co.yixiang.yshop.module.order.service.payment.PaymentOrder;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_ACTION_INVALID;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_IDEMPOTENCY_CONFLICT;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_ORDER_NOT_FOUND;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_REQUEST_INVALID;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_STATE_CONFLICT;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.FULFILLMENT_VERSION_CONFLICT;

/** Single transactional boundary for pickup fulfillment and legacy pickup receipts. */
@Service
@RequiredArgsConstructor
public class OrderFulfillmentService {

    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Map<String, String> NEXT = Map.of(
            "ACCEPT", "ACCEPTED",
            "START_PREPARING", "PREPARING",
            "MARK_READY", "READY",
            "COLLECT", "COLLECTED");

    private final JdbcTemplate jdbc;
    private final StoreAccessService storeAccess;
    private final OrderFulfillmentRewardService rewards;
    private final FulfillmentEventRepository fulfillmentEvents;

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransitionResult transitionStaff(Long orderId, String action, Integer expectedVersion, String requestKey) {
        Long actorId = SecurityFrameworkUtils.getLoginUserId();
        if (actorId == null) throw new AccessDeniedException("FULFILLMENT_ACTOR_REQUIRED");
        return transition(orderId, action, expectedVersion, requestKey, actorId, "STAFF", false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransitionResult transitionMember(Long orderId, Long memberId, String action, Integer expectedVersion, String requestKey) {
        Long authenticatedId = SecurityFrameworkUtils.getLoginUserId();
        if (memberId == null || !memberId.equals(authenticatedId)) throw new AccessDeniedException("FULFILLMENT_MEMBER_REQUIRED");
        return transition(orderId, action, expectedVersion, requestKey, memberId, "MEMBER", false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransitionResult legacyMemberTake(Long orderId, Long memberId) {
        Long authenticatedId = SecurityFrameworkUtils.getLoginUserId();
        if (memberId == null || !memberId.equals(authenticatedId)) throw new AccessDeniedException("FULFILLMENT_MEMBER_REQUIRED");
        return transition(orderId, "COLLECT", null, "legacy-member-collect-" + orderId + "-" + memberId, memberId, "MEMBER", true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransitionResult legacyStaffTake(Long orderId) {
        Long actorId = SecurityFrameworkUtils.getLoginUserId();
        if (actorId == null) throw new AccessDeniedException("FULFILLMENT_ACTOR_REQUIRED");
        return transition(orderId, "COLLECT", null, "legacy-staff-collect-" + orderId + "-" + actorId, actorId, "STAFF", true);
    }

    /** Called with the pre-payment order snapshot inside the successful payment transaction. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void initializePaidPickup(PaymentOrder paymentOrder) {
        // This is invoked only after a real 0 -> 1 payment transition. A historical paid row
        // can be LEGACY forever and must never be promoted just because this method is retried.
        if (paymentOrder == null || paymentOrder.getId() == null
                || !Integer.valueOf(0).equals(paymentOrder.getPaid())) return;
        OrderRow order = lock(paymentOrder.getId());
        if (order == null || !"takein".equals(order.orderType()) || order.paid() != 1) return;
        if (!Objects.equals(order.orderId(), paymentOrder.getOrderId()) || !Long.valueOf(order.uid()).equals(paymentOrder.getUid()))
            throw new IllegalArgumentException("FULFILLMENT_PAYMENT_ORDER_MISMATCH");
        if (order.refundStatus() != 0 || order.systemDeleted() != 0) throw stateConflict();
        String requestKey = "payment-" + order.orderId();
        String requestHash = hash(order.id(), "PAYMENT_CONFIRMED", 0, "SYSTEM");
        var prior = findAction(requestKey);
        if (prior != null) {
            if (!prior.requestHash().equals(requestHash) || prior.orderId() != order.id()) throw idempotencyConflict();
            return;
        }
        if (!"LEGACY".equals(order.fulfillmentStatus()) || order.fulfillmentVersion() != 0) throw stateConflict();
        if (jdbc.update("UPDATE yshop_store_order SET fulfillment_status='WAIT_ACCEPT',fulfillment_version=1,update_time=CURRENT_TIMESTAMP WHERE id=? AND paid=1 AND refund_status=0 AND is_system_del=0 AND deleted=0 AND order_type='takein' AND fulfillment_status='LEGACY' AND fulfillment_version=0", order.id()) != 1)
            throw stateConflict();
        insertAction(order.id(), requestKey, requestHash, "PAYMENT_CONFIRMED", "LEGACY", "WAIT_ACCEPT", 0, 1, 0L, "SYSTEM");
    }

    @Transactional(readOnly = true)
    public List<FulfillmentLogView> history(Long orderId) {
        return jdbc.query("SELECT action,from_status,to_status,expected_version,result_version,actor_id,actor_type,create_time FROM yshop_order_fulfillment_log WHERE order_id=? ORDER BY id",
                (rs, row) -> new FulfillmentLogView(rs.getString("action"), rs.getString("from_status"), rs.getString("to_status"),
                        (Integer) rs.getObject("expected_version"), rs.getInt("result_version"), rs.getLong("actor_id"),
                        rs.getString("actor_type"), rs.getTimestamp("create_time").toLocalDateTime()), orderId);
    }

    private TransitionResult transition(Long orderId, String action, Integer expectedVersion, String requestKey,
            Long actorId, String actorType, boolean legacy) {
        String next = NEXT.get(action);
        if (next == null) throw exception(FULFILLMENT_ACTION_INVALID);
        if (!legacy && (expectedVersion == null || expectedVersion < 1 || requestKey == null || !REQUEST_KEY.matcher(requestKey).matches()))
            throw exception(FULFILLMENT_REQUEST_INVALID);

        OrderRow order = lock(orderId);
        if (order == null) throw exception(FULFILLMENT_ORDER_NOT_FOUND);
        authorize(order, actorId, actorType);
        var prior = findAction(requestKey);
        String requestHash = hash(order.id(), action, expectedVersion, actorType + ":" + actorId);
        if (prior != null) {
            if (prior.orderId() != order.id() || !prior.requestHash().equals(requestHash)) throw idempotencyConflict();
            return new TransitionResult(prior.toStatus(), prior.resultVersion(), false);
        }
        if (order.paid() != 1 || order.refundStatus() != 0 || order.systemDeleted() != 0 || !"takein".equals(order.orderType()))
            throw stateConflict();
        if (expectedVersion != null && expectedVersion != order.fulfillmentVersion()) throw exception(FULFILLMENT_VERSION_CONFLICT);
        if (!expectedFrom(action).equals(order.fulfillmentStatus())) throw stateConflict();

        int nextVersion = order.fulfillmentVersion() + 1;
        if (jdbc.update("UPDATE yshop_store_order SET fulfillment_status=?,fulfillment_version=?,status=CASE WHEN ?='COLLECTED' THEN 3 ELSE status END,update_time=CURRENT_TIMESTAMP WHERE id=? AND paid=1 AND refund_status=0 AND is_system_del=0 AND deleted=0 AND order_type='takein' AND fulfillment_status=? AND fulfillment_version=?",
                next, nextVersion, next, order.id(), order.fulfillmentStatus(), order.fulfillmentVersion()) != 1)
            throw exception(FULFILLMENT_VERSION_CONFLICT);
        try {
            insertAction(order.id(), requestKey, requestHash, action, order.fulfillmentStatus(), next, expectedVersion, nextVersion, actorId, actorType);
        } catch (DataIntegrityViolationException duplicate) {
            throw idempotencyConflict();
        }
        if ("ACCEPTED".equals(next)) fulfillmentEvents.insertPickupAccepted(order.id(), nextVersion);
        if ("COLLECTED".equals(next)) rewards.award(order.toStoreOrder());
        return new TransitionResult(next, nextVersion, true);
    }

    private void authorize(OrderRow order, Long actorId, String actorType) {
        LoginUser login = SecurityFrameworkUtils.getLoginUser();
        Long contextTenant = TenantContextHolder.getTenantId();
        Long actorTenant = contextTenant != null ? contextTenant : login == null ? null : login.getTenantId();
        if (order.tenantId() != null && order.tenantId() > 0 && !order.tenantId().equals(actorTenant))
            throw new AccessDeniedException("FULFILLMENT_TENANT_DENIED");
        if ((order.tenantId() == null || order.tenantId() == 0) && actorTenant != null && actorTenant > 0)
            throw new AccessDeniedException("FULFILLMENT_TENANT_DENIED");
        if ("STAFF".equals(actorType)) storeAccess.requireShop(order.shopId());
        else if (!"MEMBER".equals(actorType) || actorId == null || actorId != order.uid())
            throw new AccessDeniedException("FULFILLMENT_MEMBER_REQUIRED");
    }

    private OrderRow lock(Long id) {
        List<OrderRow> rows = jdbc.query("SELECT id,order_id,uid,shop_id,order_type,paid,status,refund_status,is_system_del,tenant_id,fulfillment_status,fulfillment_version,gain_integral FROM yshop_store_order WHERE id=? AND deleted=0 FOR UPDATE",
                ORDER_ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ActionRow findAction(String key) {
        if (key == null) return null;
        List<ActionRow> rows = jdbc.query("SELECT order_id,request_hash,to_status,result_version FROM yshop_order_fulfillment_log WHERE request_key=?",
                (rs, row) -> new ActionRow(rs.getLong("order_id"), rs.getString("request_hash"), rs.getString("to_status"), rs.getInt("result_version")), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertAction(Long orderId, String key, String requestHash, String action, String from, String to,
            Integer expectedVersion, int resultVersion, Long actorId, String actorType) {
        jdbc.update("INSERT INTO yshop_order_fulfillment_log(order_id,request_key,request_hash,action,from_status,to_status,expected_version,result_version,actor_id,actor_type,create_time) VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                orderId, key, requestHash, action, from, to, expectedVersion, resultVersion, actorId, actorType);
    }

    private static String expectedFrom(String action) {
        return switch (action) {
            case "ACCEPT" -> "WAIT_ACCEPT";
            case "START_PREPARING" -> "ACCEPTED";
            case "MARK_READY" -> "PREPARING";
            case "COLLECT" -> "READY";
            default -> "";
        };
    }

    private static String hash(Long orderId, String action, Integer version, String actor) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((orderId + "|" + action + "|" + version + "|" + actor).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }

    private static ServiceException stateConflict() { return exception(FULFILLMENT_STATE_CONFLICT); }
    private static ServiceException idempotencyConflict() { return exception(FULFILLMENT_IDEMPOTENCY_CONFLICT); }

    private static final RowMapper<OrderRow> ORDER_ROW = (rs, row) -> new OrderRow(
            rs.getLong("id"), rs.getString("order_id"), rs.getLong("uid"), rs.getLong("shop_id"), rs.getString("order_type"),
            rs.getInt("paid"), rs.getInt("status"), rs.getInt("refund_status"), rs.getInt("is_system_del"),
            (Long) rs.getObject("tenant_id"), rs.getString("fulfillment_status"), rs.getInt("fulfillment_version"), rs.getBigDecimal("gain_integral"));

    private record OrderRow(Long id, String orderId, long uid, long shopId, String orderType, int paid, int status,
            int refundStatus, int systemDeleted, Long tenantId, String fulfillmentStatus, int fulfillmentVersion,
            java.math.BigDecimal gainIntegral) {
        StoreOrderDO toStoreOrder() { return StoreOrderDO.builder().id(id).uid(uid).gainIntegral(gainIntegral).build(); }
    }

    private record ActionRow(long orderId, String requestHash, String toStatus, int resultVersion) {}

    public record TransitionResult(String status, int version, boolean applied) {}

    public record FulfillmentLogView(String action, String fromStatus, String toStatus, Integer expectedVersion,
            int resultVersion, Long actorId, String actorType, LocalDateTime createTime) {}

}
