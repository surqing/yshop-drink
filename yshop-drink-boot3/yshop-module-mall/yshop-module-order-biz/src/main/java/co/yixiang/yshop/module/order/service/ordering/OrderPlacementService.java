package co.yixiang.yshop.module.order.service.ordering;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;

import co.yixiang.yshop.framework.common.exception.ErrorCode;
import co.yixiang.yshop.module.order.controller.app.order.param.AppOrderParam;
import co.yixiang.yshop.module.product.service.catalog.CatalogOptions;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.*;
import java.util.*;

/** Single-business ordering. SQL locks and conditional writes are the authority; no Redis lock. */
@Service
@RequiredArgsConstructor
public class OrderPlacementService {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final co.yixiang.yshop.module.order.service.payment.attempt.PaymentCancellationGuard
            cancellationGuard;
    private Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    record Line(long productId, String sku, int quantity, CatalogOptions.Choice choice) {}

    record PricedLine(Line line, long skuId, BigDecimal price, String title, String image, String snapshot) {}

    private static RuntimeException reject(String code) {
        return exception(new ErrorCode(1008003090, code));
    }

    private static long positive(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,17}"))
                throw reject("INVALID_ORDER_INPUT");
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw reject("INVALID_ORDER_INPUT");
        }
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        if (rows.size() != 1) throw reject("ORDER_RESOURCE_UNAVAILABLE");
        return rows.get(0);
    }

    private static long number(Map<String, Object> row, String name) {
        Object v = row.get(name);
        if (v instanceof Boolean b) return b ? 1 : 0;
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(v.toString());
    }

    private static LocalDateTime dateTime(Object value) {
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof java.sql.Timestamp stamp) return stamp.toLocalDateTime();
        throw reject("INVALID_SERVER_TIME");
    }

    private static BigDecimal money(Object value) {
        try {
            BigDecimal amount = value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
            if (amount.signum() < 0) throw reject("INVALID_SERVER_PRICE");
            return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw reject("INVALID_SERVER_PRICE");
        }
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        if (jdbc.update(
                                c -> {
                                    var p =
                                            c.prepareStatement(
                                                    sql, Statement.RETURN_GENERATED_KEYS);
                                    for (int i = 0; i < args.length; i++)
                                        p.setObject(i + 1, args[i]);
                                    return p;
                                },
                                key)
                        != 1
                || key.getKey() == null) throw reject("ORDER_INSERT_FAILED");
        return key.getKey().longValue();
    }

    public Map<String, Object> place(Long uid, AppOrderParam p) {
        if (uid == null || uid <= 0 || p == null) throw reject("INVALID_ORDER_INPUT");
        long shopId = positive(p.getShopId());
        if (!Set.of("takein", "takeout").contains(Objects.toString(p.getOrderType(), "")))
            throw reject("UNSUPPORTED_ORDER_TYPE");
        if (p.getIdempotencyKey() == null || !p.getIdempotencyKey().matches("[A-Za-z0-9_-]{16,64}"))
            throw reject("ORDER_IDEMPOTENCY_KEY_REQUIRED");
        if (p.getProductId() == null
                || p.getNumber() == null
                || p.getSpec() == null
                || p.getProductId().isEmpty()
                || p.getProductId().size() > 100
                || p.getProductId().size() != p.getNumber().size()
                || p.getProductId().size() != p.getSpec().size())
            throw reject("INVALID_ORDER_LINES");
        int minutes = p.getGettime() == null ? 0 : p.getGettime();
        if (minutes < 0
                || minutes > 1440
                || (p.getRemark() != null && p.getRemark().length() > 200))
            throw reject("INVALID_ORDER_INPUT");
        List<Line> lines = new ArrayList<>();
        if (p.getChoices()!=null && p.getChoices().size()!=p.getProductId().size())
            throw reject("INVALID_ORDER_CHOICES");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < p.getProductId().size(); i++) {
            long product = positive(p.getProductId().get(i)), qty = positive(p.getNumber().get(i));
            String sku = p.getSpec().get(i);
            if (qty > 999 || sku == null || sku.length() > 256) throw reject("INVALID_ORDER_LINES");
            sku = sku.replace('|', ',');
            var choice=p.getChoices()==null?null:p.getChoices().get(i);
            if(choice!=null) {
                if(choice.selections()==null || choice.selections().size()>100 || choice.selections().stream().anyMatch(Objects::isNull)) throw reject("INVALID_ORDER_CHOICES");
                choice=new CatalogOptions.Choice(choice.version(),choice.selections().stream().sorted(Comparator.comparing(CatalogOptions.Selection::groupId,Comparator.nullsFirst(String::compareTo)).thenComparing(CatalogOptions.Selection::optionId,Comparator.nullsFirst(String::compareTo))).toList());
            }
            if (!seen.add(product + ":" + sku+":"+Objects.toString(choice,""))) throw reject("DUPLICATE_ORDER_LINE");
            lines.add(new Line(product, sku, (int) qty,choice));
        }
        lines.sort(Comparator.comparingLong(Line::productId).thenComparing(Line::sku));
        String canonical =
                co.yixiang.yshop.framework.common.util.json.JsonUtils.toJsonString(
                        List.of(
                                shopId,
                                p.getOrderType(),
                                Objects.toString(p.getAddressId(), "0"),
                                Objects.toString(p.getCouponId(), "0"),
                                minutes,
                                Objects.toString(p.getRemark(), ""),
                                lines.stream()
                                        .map(
                                                line ->
                                                        line.choice()==null?List.of(line.productId(),line.sku(),line.quantity()):List.of(
                                                                line.productId(),
                                                                line.sku(),
                                                                line.quantity(),Objects.toString(line.choice(),"")))
                                        .toList()));
        String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        return new TransactionTemplate(transactions)
                .execute(tx -> create(uid, p, shopId, minutes, lines, hash));
    }

    private Map<String, Object> create(
            Long uid, AppOrderParam p, long shopId, int minutes, List<Line> lines, String hash) {
        String generated = UUID.randomUUID().toString().replace("-", "");
        // Upsert acquires the unique submission row; do not gap-lock a missing key before
        // inserting.
        jdbc.update(
                "INSERT INTO yshop_order_submission(uid,idempotency_key,request_hash,order_id)"
                    + " VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE idempotency_key=idempotency_key",
                uid,
                p.getIdempotencyKey(),
                hash,
                generated);
        var submission =
                one(
                        "SELECT request_hash,order_id FROM yshop_order_submission WHERE uid=? AND"
                                + " idempotency_key=? FOR UPDATE",
                        uid,
                        p.getIdempotencyKey());
        if (!hash.equals(submission.get("request_hash")))
            throw reject("ORDER_IDEMPOTENCY_CONFLICT");
        String orderId = submission.get("order_id").toString();
        if (!generated.equals(orderId)) return Map.of("orderId", orderId);
        one("SELECT id FROM yshop_user WHERE id=? AND deleted=0", uid);
        var shop =
                one("SELECT * FROM yshop_store_shop WHERE id=? AND deleted=0 FOR UPDATE", shopId);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalTime start = dateTime(shop.get("start_time")).toLocalTime();
        LocalTime end = dateTime(shop.get("end_time")).toLocalTime();
        LocalTime time = now.toLocalTime();
        boolean open =
                start.equals(end)
                        || (start.isBefore(end)
                                ? !time.isBefore(start) && time.isBefore(end)
                                : !time.isBefore(start) || time.isBefore(end));
        if (number(shop, "status") != 1 || !open) throw reject("STORE_CLOSED");
        List<PricedLine> priced = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO, integral = BigDecimal.ZERO;
        for (Line line : lines) {
            var product =
                    one(
                            "SELECT * FROM yshop_store_product WHERE id=? AND deleted=0 FOR UPDATE",
                            line.productId());
            if (number(product, "shop_id") != shopId
                    || number(product, "is_show") != 1
                    || (product.get("is_integral") != null && number(product, "is_integral") != 0))
                throw reject("PRODUCT_NOT_AVAILABLE_IN_STORE");
            long categoryId = positive(product.get("cate_id").toString());
            Set<Long> visitedCategories = new HashSet<>();
            while (categoryId > 0) {
                if (!visitedCategories.add(categoryId) || visitedCategories.size() > 2)
                    throw reject("INVALID_CATEGORY_HIERARCHY");
                var category =
                        one(
                                "SELECT shop_id,status,parent_id FROM yshop_store_product_category"
                                        + " WHERE id=? AND deleted=0 FOR UPDATE",
                                categoryId);
                if (number(category, "shop_id") != shopId || number(category, "status") != 0)
                    throw reject("CATEGORY_NOT_AVAILABLE_IN_STORE");
                categoryId = number(category, "parent_id");
            }
            var sku =
                    one(
                            "SELECT * FROM yshop_store_product_attr_value WHERE"
                                    + " product_id=? AND sku=? FOR UPDATE",
                            line.productId(),
                            line.sku());
            if(sku.containsKey("is_show") && number(sku,"is_show")!=1) throw reject("SKU_NOT_AVAILABLE");
            var quote=CatalogOptions.quote(product,line.choice());
            BigDecimal basePrice=money(sku.get("price"));
            BigDecimal price = basePrice.add(quote.extra());
            if (basePrice.signum() <= 0 || price.signum() <= 0 || number(sku, "stock") < line.quantity())
                throw reject("PRODUCT_STOCK_OR_PRICE_INVALID");
            if (jdbc.update(
                                    "UPDATE yshop_store_product SET stock=stock-?,sales=sales+?"
                                        + " WHERE id=? AND shop_id=? AND deleted=0 AND is_show=1"
                                        + " AND stock>=?",
                                    line.quantity(),
                                    line.quantity(),
                                    line.productId(),
                                    shopId,
                                    line.quantity())
                            != 1
                    || jdbc.update(
                                    "UPDATE yshop_store_product_attr_value SET"
                                        + " stock=stock-?,sales=sales+? WHERE id=? AND product_id=?"
                                        + " AND stock>=?",
                                    line.quantity(),
                                    line.quantity(),
                                    number(sku, "id"),
                                    line.productId(),
                                    line.quantity())
                            != 1) throw reject("PRODUCT_STOCK_INSUFFICIENT");
            priced.add(
                    new PricedLine(
                            line,
                            number(sku, "id"),
                            price,
                            product.get("store_name").toString(),
                            Objects.toString(product.get("image"), ""),
                            co.yixiang.yshop.framework.common.util.json.JsonUtils.toJsonString(Map.ofEntries(
                                Map.entry("version",1),Map.entry("productId",line.productId()),Map.entry("shopId",shopId),
                                Map.entry("title",product.get("store_name")),Map.entry("skuId",number(sku,"id")),Map.entry("sku",line.sku()),
                                Map.entry("catalogVersion",product.getOrDefault("catalog_version",0L)),Map.entry("basePrice",basePrice),
                                Map.entry("options",quote.selected()),Map.entry("optionExtra",quote.extra()),Map.entry("unitPrice",price),
                                Map.entry("quantity",line.quantity()),Map.entry("lineTotal",price.multiply(BigDecimal.valueOf(line.quantity())))))));
            total = total.add(price.multiply(BigDecimal.valueOf(line.quantity())));
            integral =
                    integral.add(
                            money(product.get("give_integral"))
                                    .multiply(BigDecimal.valueOf(line.quantity())));
        }
        String name = "", phone = "", address = "";
        BigDecimal postage = BigDecimal.ZERO;
        if ("takeout".equals(p.getOrderType())) {
            if (number(shop, "distance") <= 0 || total.compareTo(money(shop.get("min_price"))) < 0)
                throw reject("DELIVERY_NOT_AVAILABLE");
            var a =
                    one(
                            "SELECT real_name,phone,address,detail FROM yshop_user_address WHERE"
                                    + " id=? AND uid=? AND deleted=0",
                            positive(p.getAddressId()),
                            uid);
            name = Objects.toString(a.get("real_name"), "");
            phone = Objects.toString(a.get("phone"), "");
            address =
                    Objects.toString(a.get("address"), "")
                            + " "
                            + Objects.toString(a.get("detail"), "");
            postage = money(shop.get("delivery_price"));
        }
        BigDecimal discount = BigDecimal.ZERO;
        long coupon = 0;
        if (p.getCouponId() != null && !p.getCouponId().isBlank() && !"0".equals(p.getCouponId())) {
            coupon = positive(p.getCouponId());
            var c =
                    one(
                            "SELECT *, (type+0) AS coupon_type FROM yshop_coupon_user WHERE id=?"
                                    + " AND user_id=? AND deleted=0 FOR UPDATE",
                            coupon,
                            uid);
            Set<String> shops =
                    new HashSet<>(Arrays.asList(Objects.toString(c.get("shop_id"), "").split(",")));
            if (number(c, "status") != 0
                    || c.get("reserved_order_id") != null
                    || (!shops.contains("0") && !shops.contains(Long.toString(shopId)))
                    || dateTime(c.get("start_time")).isAfter(now)
                    || !dateTime(c.get("end_time")).isAfter(now)
                    || total.compareTo(money(c.get("least"))) < 0
                    || (number(c, "coupon_type") != 0
                            && number(c, "coupon_type")
                                    != ("takein".equals(p.getOrderType()) ? 1 : 2)))
                throw reject("COUPON_NOT_AVAILABLE");
            discount = money(c.get("value")).min(total);
            if (jdbc.update(
                            "UPDATE yshop_coupon_user SET status=1,reserved_order_id=? WHERE id=?"
                                    + " AND user_id=? AND status=0 AND reserved_order_id IS NULL",
                            orderId,
                            coupon,
                            uid)
                    != 1) throw reject("COUPON_NOT_AVAILABLE");
        }
        BigDecimal pay = total.add(postage).subtract(discount);
        // Free checkout is not implicitly a wallet payment. Leave it unpaid for a later explicit
        // policy.
        if (pay.signum() <= 0) throw reject("ZERO_VALUE_ORDER_NOT_SUPPORTED");
        long numberId = insert("INSERT INTO yshop_order_number(order_id) VALUES(?)", orderId);
        long id =
                insert(
                        "INSERT INTO"
                            + " yshop_store_order(order_id,shop_id,shop_name,uid,real_name,user_phone,user_address,total_num,total_price,total_postage,pay_price,pay_postage,coupon_id,coupon_price,paid,pay_type,order_type,gain_integral,mark,cost,get_time,number_id,create_time,update_time,ordering_version)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,'weixin',?,?,?,?,?,?,?,?,1)",
                        orderId,
                        shopId,
                        shop.get("name"),
                        uid,
                        name,
                        phone,
                        address,
                        lines.stream().mapToInt(Line::quantity).sum(),
                        total,
                        postage,
                        pay,
                        postage,
                        coupon,
                        discount,
                        p.getOrderType(),
                        integral,
                        Objects.toString(p.getRemark(), ""),
                        BigDecimal.ZERO,
                        now.plusMinutes(minutes),
                        numberId,
                        now,
                        now);
        for (int i = 0; i < priced.size(); i++) {
            var l = priced.get(i);
            jdbc.update(
                    "INSERT INTO"
                        + " yshop_store_order_cart_info(oid,order_id,product_id,cart_info,`unique`,is_after_sales,title,image,number,price,spec)"
                        + " VALUES(?,?,?,?,?,1,?,?,?,?,?)",
                    id,
                    orderId,
                    l.line().productId(),
                    l.snapshot(),
                    UUID.randomUUID().toString().replace("-", ""),
                    l.title(),
                    l.image(),
                    l.line().quantity(),
                    l.price(),
                    l.line().sku());
            jdbc.update(
                    "INSERT INTO"
                        + " yshop_order_inventory_reservation(order_id,line_no,product_id,sku_id,quantity)"
                        + " VALUES(?,?,?,?,?)",
                    orderId,
                    i,
                    l.line().productId(),
                    l.skuId(),
                    l.line().quantity());
        }
        jdbc.update(
                "INSERT INTO yshop_store_order_status(oid,change_type,change_message)"
                        + " VALUES(?,'create_order','创建待支付订单')",
                id);
        return Map.of("orderId", orderId);
    }

    public boolean ownsVersion(String orderId) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM yshop_store_order WHERE order_id=? AND"
                                + " ordering_version=1",
                        Long.class,
                        orderId)
                > 0;
    }

    private TransactionTemplate cancellationTransaction() {
        var cancellation = new TransactionTemplate(transactions);
        cancellation.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return cancellation;
    }

    public void cancel(String orderId, Long uid, boolean expiredOnly) {
        cancellationTransaction()
                .executeWithoutResult(
                        tx -> {
                            var order =
                                    one(
                                            "SELECT * FROM yshop_store_order WHERE order_id=? AND"
                                                    + " ordering_version=1 FOR UPDATE",
                                            orderId);
                            if (uid != null && number(order, "uid") != uid)
                                throw reject("ORDER_NOT_AVAILABLE");
                            if (uid == null && !expiredOnly) throw reject("ORDER_OWNER_REQUIRED");
                            if (number(order, "paid") != 0
                                    || number(order, "status") != 0
                                    || number(order, "refund_status") != 0)
                                throw reject("ORDER_NOT_CANCELABLE");
                            if (expiredOnly
                                    && dateTime(order.get("create_time"))
                                            .isAfter(LocalDateTime.now(clock).minusMinutes(30)))
                                throw reject("ORDER_NOT_EXPIRED");
                            cancellationGuard.assertSafeAfterOrderLock(orderId);
                            if (jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM yshop_store_order WHERE"
                                                    + " order_id=? AND deleted=1",
                                            Long.class,
                                            orderId)
                                    > 0) return;
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT * FROM yshop_order_inventory_reservation WHERE"
                                                    + " order_id=? ORDER BY product_id,sku_id",
                                            orderId);
                            if (rows.isEmpty()) throw reject("ORDER_INVENTORY_EVIDENCE_MISSING");
                            for (var line : rows) {
                                if (line.get("released_at") != null)
                                    throw reject("ORDER_INVENTORY_INCONSISTENT");
                                if (jdbc.update(
                                                        "UPDATE yshop_store_product SET"
                                                            + " stock=stock+?,sales=sales-? WHERE"
                                                            + " id=? AND shop_id=? AND sales>=?",
                                                        line.get("quantity"),
                                                        line.get("quantity"),
                                                        line.get("product_id"),
                                                        order.get("shop_id"),
                                                        line.get("quantity"))
                                                != 1
                                        || jdbc.update(
                                                        "UPDATE yshop_store_product_attr_value SET"
                                                            + " stock=stock+?,sales=sales-? WHERE"
                                                            + " id=? AND product_id=? AND sales>=?",
                                                        line.get("quantity"),
                                                        line.get("quantity"),
                                                        line.get("sku_id"),
                                                        line.get("product_id"),
                                                        line.get("quantity"))
                                                != 1) throw reject("ORDER_STOCK_RESTORE_FAILED");
                            }
                            jdbc.update(
                                    "UPDATE yshop_order_inventory_reservation SET"
                                            + " released_at=CURRENT_TIMESTAMP WHERE order_id=? AND"
                                            + " released_at IS NULL",
                                    orderId);
                            if (number(order, "coupon_id") > 0
                                    && jdbc.update(
                                                    "UPDATE yshop_coupon_user SET"
                                                        + " status=0,reserved_order_id=NULL WHERE"
                                                        + " id=? AND user_id=? AND"
                                                        + " reserved_order_id=? AND status=1",
                                                    order.get("coupon_id"),
                                                    order.get("uid"),
                                                    orderId)
                                            != 1) throw reject("ORDER_COUPON_RESTORE_FAILED");
                            if (jdbc.update(
                                            "UPDATE yshop_store_order SET"
                                                + " deleted=1,update_time=CURRENT_TIMESTAMP WHERE"
                                                + " id=? AND paid=0 AND status=0 AND"
                                                + " refund_status=0 AND deleted=0",
                                            order.get("id"))
                                    != 1) throw reject("ORDER_CANCEL_CONFLICT");
                            jdbc.update(
                                    "INSERT INTO"
                                        + " yshop_store_order_status(oid,change_type,change_message)"
                                        + " VALUES(?,'cancel_order',?)",
                                    order.get("id"),
                                    expiredOnly ? "未支付订单超时取消" : "顾客取消未支付订单");
                        });
    }

    public int expireBatch() {
        var ids =
                jdbc.queryForList(
                        "SELECT order_id FROM yshop_store_order WHERE ordering_version=1 AND paid=0"
                            + " AND status=0 AND refund_status=0 AND deleted=0 AND create_time<=?"
                            + " ORDER BY id LIMIT 100",
                        String.class,
                        LocalDateTime.now(clock).minusMinutes(30));
        int completed = 0;
        for (String id : ids) {
            try {
                cancel(id, null, true);
                completed++;
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(OrderPlacementService.class)
                        .warn(
                                "Unpaid order expiry deferred; orderId={}, failureType={}",
                                id,
                                ex.getClass().getSimpleName());
            }
        }
        return completed;
    }
}
