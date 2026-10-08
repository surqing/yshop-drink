package co.yixiang.yshop.module.store.service.storeshop;

import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.module.system.api.permission.PermissionApi;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.*;

/** Fresh server-side shop assignments, independent of client shopId and stale token hints. */
@Service
@RequiredArgsConstructor
public class StoreAccessService {
    private final JdbcTemplate jdbc;
    private final PermissionApi permissions;

    public boolean headquarters() {
        var user = SecurityFrameworkUtils.getLoginUser();
        if (user == null || !Integer.valueOf(2).equals(user.getUserType()))
            throw new AccessDeniedException("STORE_ACCESS_DENIED");
        return permissions.hasAnyRoles(user.getId(), "super_admin", "business_headquarters");
    }

    /** null means explicitly authorized headquarters; an unassigned ordinary account is denied. */
    public Set<Long> allowedShopIds() {
        if (headquarters()) return null;
        Long uid = SecurityFrameworkUtils.getLoginUserId();
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM yshop_store_shop WHERE deleted=0 AND"
                                + " CONCAT(',',admin_id,',') LIKE ?",
                        Long.class,
                        "%," + uid + ",%");
        if (ids.isEmpty()) throw new AccessDeniedException("STORE_ASSIGNMENT_REQUIRED");
        return new LinkedHashSet<>(ids);
    }

    public void requireHeadquarters() {
        if (!headquarters()) throw new AccessDeniedException("HEADQUARTERS_REQUIRED");
    }

    public void requireShop(Long shopId) {
        if (shopId == null || shopId <= 0)
            throw new AccessDeniedException("STORE_CONTEXT_REQUIRED");
        var allowed = allowedShopIds();
        if (allowed != null && !allowed.contains(shopId))
            throw new AccessDeniedException("STORE_ACCESS_DENIED");
    }

    public void requireOrder(Long id) {
        requireRow("yshop_store_order", id);
    }

    public void requireProduct(Long id) {
        requireRow("yshop_store_product", id);
    }

    public void requireProductEditable(Long id) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("PRODUCT_EDIT_TRANSACTION_REQUIRED");
        jdbc.queryForObject(
                "SELECT id FROM yshop_store_product WHERE id=? AND deleted=0 FOR UPDATE",
                Long.class,
                id);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM yshop_order_inventory_reservation r JOIN"
                            + " yshop_store_order o ON o.order_id=r.order_id WHERE r.product_id=?"
                            + " AND r.released_at IS NULL AND o.paid=0 AND o.deleted=0",
                        Long.class,
                        id)
                > 0) throw new AccessDeniedException("PRODUCT_HAS_UNPAID_RESERVATIONS");
    }

    public void requireCategory(Long id) {
        requireRow("yshop_store_product_category", id);
    }

    private void requireRow(String table, Long id) {
        var ids = jdbc.queryForList("SELECT shop_id FROM " + table + " WHERE id=?", Long.class, id);
        if (ids.size() != 1) throw new AccessDeniedException("STORE_RESOURCE_UNAVAILABLE");
        requireShop(ids.get(0));
    }
}
