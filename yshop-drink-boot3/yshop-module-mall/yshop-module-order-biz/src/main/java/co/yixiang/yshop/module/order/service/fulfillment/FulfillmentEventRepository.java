package co.yixiang.yshop.module.order.service.fulfillment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists the immutable print intent in the same transaction as pickup acceptance. */
@Repository
@RequiredArgsConstructor
public class FulfillmentEventRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public void insertPickupAccepted(long orderId, int fulfillmentVersion) {
        PrintOrder order = jdbc.queryForObject("SELECT tenant_id,shop_id,order_id,shop_name,number_id,user_phone,total_num,pay_price,create_time FROM yshop_store_order WHERE id=?",
                (rs, row) -> new PrintOrder(rs.getObject("tenant_id", Long.class), rs.getLong("shop_id"), rs.getString("order_id"),
                        rs.getString("shop_name"), rs.getObject("number_id", Integer.class), rs.getString("user_phone"),
                        rs.getObject("total_num", Integer.class), rs.getBigDecimal("pay_price"),
                        rs.getObject("create_time", java.time.LocalDateTime.class)), orderId);
        List<Map<String, Object>> items = jdbc.query("SELECT title,number,price,spec,cart_info FROM yshop_store_order_cart_info WHERE oid=? ORDER BY id",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", rs.getString("title"));
                    item.put("quantity", rs.getInt("number"));
                    item.put("unitPrice", rs.getBigDecimal("price"));
                    item.put("specification", rs.getString("spec"));
                    item.put("cartDetails", rs.getString("cart_info"));
                    return item;
                }, orderId);

        long tenantId = order.tenantId() == null ? 0L : order.tenantId();
        long shopId = order.shopId();
        String eventKey = "pickup-accepted:" + orderId + ":" + fulfillmentVersion;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("orderId", orderId);
        payload.put("orderCode", order.orderCode());
        payload.put("shopName", order.shopName());
        payload.put("pickupNumber", order.pickupNumber());
        payload.put("contactPhone", maskPhone(order.contactPhone()));
        payload.put("totalQuantity", order.totalQuantity());
        payload.put("paidAmount", order.paidAmount());
        payload.put("orderCreatedAt", order.createdAt() == null ? null : order.createdAt().toString());
        payload.put("items", items);
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("FULFILLMENT_PRINT_SNAPSHOT_SERIALIZATION_FAILED", failure);
        }
        jdbc.update("INSERT INTO yshop_order_fulfillment_print_event(tenant_id,order_id,shop_id,event_key,event_type,payload_snapshot,state,attempts,create_time,update_time) VALUES(?,?,?,?,? ,?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                tenantId, orderId, shopId, eventKey, "PICKUP_ACCEPTED", snapshot);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "";
        if (phone.matches("1[3-9][0-9]{9}")) return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 4) return "****";
        return "****" + digits.substring(digits.length() - 4);
    }

    private record PrintOrder(Long tenantId, long shopId, String orderCode, String shopName, Integer pickupNumber,
            String contactPhone, Integer totalQuantity, BigDecimal paidAmount, java.time.LocalDateTime createdAt) {}
}
