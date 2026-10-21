package co.yixiang.yshop.module.order.service.fulfillment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
        Map<String, Object> order = jdbc.queryForMap("SELECT tenant_id,shop_id,order_id,shop_name,number_id,user_phone,total_num,pay_price,create_time FROM yshop_store_order WHERE id=?", orderId);
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

        long tenantId = order.get("tenant_id") == null ? 0L : ((Number) order.get("tenant_id")).longValue();
        long shopId = ((Number) order.get("shop_id")).longValue();
        String eventKey = "pickup-accepted:" + orderId + ":" + fulfillmentVersion;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("orderId", orderId);
        payload.put("orderCode", order.get("order_id"));
        payload.put("shopName", order.get("shop_name"));
        payload.put("pickupNumber", order.get("number_id"));
        payload.put("contactPhone", maskPhone((String) order.get("user_phone")));
        payload.put("totalQuantity", order.get("total_num"));
        payload.put("paidAmount", order.get("pay_price"));
        Timestamp createTime = (Timestamp) order.get("create_time");
        payload.put("orderCreatedAt", createTime == null ? null : createTime.toLocalDateTime().toString());
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
}
