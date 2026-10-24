package co.yixiang.yshop.module.order.service.statistics;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant and shop scoped operational statistics derived from paid order rows. */
@Service
public class StoreOrderStatisticsService {
    private static final int MAX_RANGE_DAYS = 366;

    private final JdbcTemplate jdbc;
    private final StoreAccessService storeAccess;
    private final boolean tenantEnabled;

    public StoreOrderStatisticsService(JdbcTemplate jdbc, StoreAccessService storeAccess,
                                       @Value("${yshop.tenant.enable:false}") boolean tenantEnabled) {
        this.jdbc = jdbc;
        this.storeAccess = storeAccess;
        this.tenantEnabled = tenantEnabled;
    }

    @Transactional(readOnly = true)
    public Summary summary(Long requestedShopId, LocalDate from, LocalDate to) {
        TimeRange range = timeRange(from, to);
        Scope scope = scope(requestedShopId);
        List<Object> args = new ArrayList<>();
        args.add(range.start());
        args.add(range.endExclusive());
        args.addAll(scope.arguments());
        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT COUNT(*) AS paid_order_count, COALESCE(SUM(pay_price),0) AS gross_paid_amount, "
                        + "COALESCE(SUM(total_num),0) AS sold_quantity, "
                        + "SUM(CASE WHEN refund_status IN (1,2) THEN 1 ELSE 0 END) AS refund_order_count, "
                        + "SUM(CASE WHEN refund_status=1 THEN 1 ELSE 0 END) AS refund_pending_count, "
                        + "SUM(CASE WHEN refund_status=2 THEN 1 ELSE 0 END) AS refund_completed_count, "
                        + "COALESCE(SUM(CASE WHEN refund_status IN (1,2) THEN refund_price ELSE 0 END),0) AS refund_amount "
                        + "FROM yshop_store_order WHERE shop_id IS NOT NULL AND paid=1 AND pay_time>=? AND pay_time<? "
                        + "AND refund_status IN (0,1,2) AND is_system_del=0 AND deleted=0 " + scope.predicate(),
                args.toArray());
        // Use a separate query for the live work queue: older paid pickup orders still need action.
        List<Object> queueArgs = new ArrayList<>(scope.arguments());
        List<Map<String, Object>> queueRows = jdbc.queryForList(
                "SELECT fulfillment_status,COUNT(*) AS item_count FROM yshop_store_order "
                        + "WHERE shop_id IS NOT NULL AND paid=1 AND refund_status=0 AND is_system_del=0 AND deleted=0 "
                        + "AND order_type='takein' AND fulfillment_status IN ('WAIT_ACCEPT','ACCEPTED','PREPARING','READY') "
                        + scope.predicate() + " GROUP BY fulfillment_status",
                queueArgs.toArray());
        Map<String, Long> queues = Map.of("WAIT_ACCEPT", 0L, "ACCEPTED", 0L, "PREPARING", 0L, "READY", 0L);
        java.util.HashMap<String, Long> counts = new java.util.HashMap<>(queues);
        for (Map<String, Object> row : queueRows) counts.put((String) row.get("fulfillment_status"), ((Number) row.get("item_count")).longValue());
        return new Summary(scope.authorizedShopIds(), scope.headquarters(), from, to, LocalDateTime.now(),
                number(totals, "paid_order_count").longValue(), decimal(totals, "gross_paid_amount"),
                number(totals, "sold_quantity").longValue(), number(totals, "refund_order_count").longValue(),
                number(totals, "refund_pending_count").longValue(), number(totals, "refund_completed_count").longValue(),
                decimal(totals, "refund_amount"), counts.get("WAIT_ACCEPT"), counts.get("ACCEPTED"),
                counts.get("PREPARING"), counts.get("READY"));
    }

    @Transactional(readOnly = true)
    public List<ShopOption> authorizedShops() {
        Scope scope = scope(null);
        String sql;
        Object[] args;
        if (scope.headquarters()) {
            // yshop_store_shop is global and has no tenant_id; expose only shops represented by this tenant's orders.
            sql = "SELECT s.id,s.name FROM yshop_store_shop s WHERE s.deleted=0 AND EXISTS ("
                    + "SELECT 1 FROM yshop_store_order o WHERE o.shop_id=s.id AND o.deleted=0 "
                    + "AND o.is_system_del=0 AND COALESCE(o.tenant_id,0)=?)";
            args = new Object[] {scope.arguments().get(0)};
        } else {
            sql = "SELECT id,name FROM yshop_store_shop WHERE deleted=0";
            String placeholders = String.join(",", java.util.Collections.nCopies(scope.authorizedShopIds().size(), "?"));
            sql += " AND id IN (" + placeholders + ")";
            args = scope.authorizedShopIds().toArray();
        }
        sql += scope.headquarters() ? " ORDER BY s.name,s.id" : " ORDER BY name,id";
        return jdbc.query(sql, (rs, row) -> new ShopOption(rs.getLong("id"), rs.getString("name")), args);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderDetail> details(Long requestedShopId, LocalDate from, LocalDate to, int pageNo, int pageSize) {
        TimeRange range = timeRange(from, to);
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("INVALID_STATISTICS_PAGE");
        Scope scope = scope(requestedShopId);
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(range.start());
        countArgs.add(range.endExclusive());
        countArgs.addAll(scope.arguments());
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yshop_store_order WHERE shop_id IS NOT NULL AND paid=1 AND pay_time>=? AND pay_time<? "
                        + "AND refund_status IN (0,1,2) AND is_system_del=0 AND deleted=0 " + scope.predicate(),
                Long.class, countArgs.toArray());
        List<Object> rowsArgs = new ArrayList<>(countArgs);
        rowsArgs.add(pageSize);
        rowsArgs.add((long) (pageNo - 1) * pageSize);
        List<OrderDetail> rows = jdbc.query(
                "SELECT id,order_id,shop_id,pay_time,pay_price,refund_status,refund_price,total_num,fulfillment_status "
                        + "FROM yshop_store_order WHERE shop_id IS NOT NULL AND paid=1 AND pay_time>=? AND pay_time<? "
                        + "AND refund_status IN (0,1,2) AND is_system_del=0 AND deleted=0 "
                        + scope.predicate() + " ORDER BY pay_time DESC,id DESC LIMIT ? OFFSET ?",
                ORDER_DETAIL, rowsArgs.toArray());
        return new PageResult<>(rows, Objects.requireNonNullElse(total, 0L));
    }

    private Scope scope(Long requestedShopId) {
        LoginUser user = SecurityFrameworkUtils.getLoginUser();
        Long contextTenant = TenantContextHolder.getTenantId();
        if (user == null || user.getId() == null) throw new AccessDeniedException("STORE_TENANT_REQUIRED");
        Long userTenant = user.getTenantId();
        long tenant;
        if (userTenant == null) {
            if (tenantEnabled || contextTenant != null) throw new AccessDeniedException("STORE_TENANT_REQUIRED");
            tenant = 0L;
        } else {
            if ((tenantEnabled && contextTenant == null) || (contextTenant != null && !contextTenant.equals(userTenant)))
                throw new AccessDeniedException("STORE_TENANT_MISMATCH");
            tenant = userTenant;
        }
        var allowed = storeAccess.allowedShopIds();
        if (requestedShopId != null) {
            storeAccess.requireShop(requestedShopId);
            return new Scope(List.of(requestedShopId), allowed == null,
                    " AND COALESCE(tenant_id,0)=? AND shop_id=?", List.of(tenant, requestedShopId));
        }
        if (allowed == null) {
            return new Scope(null, true, " AND COALESCE(tenant_id,0)=?", List.of(tenant));
        }
        List<Long> ids = allowed.stream().sorted().toList();
        if (ids.isEmpty()) throw new AccessDeniedException("STORE_ASSIGNMENT_REQUIRED");
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenant);
        arguments.addAll(ids);
        return new Scope(ids, false, " AND COALESCE(tenant_id,0)=? AND shop_id IN (" + placeholders + ")", arguments);
    }

    private static TimeRange timeRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS)
            throw new IllegalArgumentException("INVALID_STATISTICS_DATE_RANGE");
        // DB pay_time is stored in Asia/Shanghai local time; dates form a left-closed, right-open interval.
        return new TimeRange(from.atStartOfDay(), to.atStartOfDay());
    }

    private static Number number(Map<String, Object> values, String key) {
        return (Number) Objects.requireNonNullElse(values.get(key), 0L);
    }

    private static BigDecimal decimal(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal amount ? amount : new BigDecimal(value.toString());
    }

    private static final RowMapper<OrderDetail> ORDER_DETAIL = (ResultSet rs, int row) -> new OrderDetail(
            rs.getLong("id"), rs.getString("order_id"), rs.getLong("shop_id"),
            rs.getTimestamp("pay_time").toLocalDateTime(), rs.getBigDecimal("pay_price"), rs.getInt("refund_status"),
            rs.getBigDecimal("refund_price"), rs.getInt("total_num"), rs.getString("fulfillment_status"));

    private record Scope(List<Long> authorizedShopIds, boolean headquarters, String predicate, List<Object> arguments) {}
    private record TimeRange(LocalDateTime start, LocalDateTime endExclusive) {}

    public record Summary(List<Long> authorizedShopIds, boolean headquarters, LocalDate from, LocalDate to,
                          LocalDateTime generatedAt, long paidOrderCount, BigDecimal grossPaidAmount,
                          long soldQuantity, long refundOrderCount, long refundPendingCount,
                          long refundCompletedCount, BigDecimal refundAmount, long waitAcceptQueueCount,
                          long acceptedQueueCount, long preparingQueueCount, long readyQueueCount) {}

    public record OrderDetail(long id, String orderId, Long shopId, LocalDateTime payTime, BigDecimal payPrice,
                              int refundStatus, BigDecimal refundPrice, int totalNum, String fulfillmentStatus) {}

    public record ShopOption(long id, String name) {}
}
