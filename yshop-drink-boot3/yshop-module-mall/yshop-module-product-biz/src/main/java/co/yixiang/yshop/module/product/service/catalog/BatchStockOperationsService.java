package co.yixiang.yshop.module.product.service.catalog;

import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static co.yixiang.yshop.module.product.service.catalog.CatalogOptions.reject;

/** Atomic operations over existing AVAILABLE SKU stock; reservations and sales remain order-owned. */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
public class BatchStockOperationsService {
    private static final long MAX_SKU_STOCK = 1_000_000L;
    private static final long MAX_PRODUCT_STOCK = Integer.MAX_VALUE;

    private final JdbcTemplate jdbc;
    private final StoreAccessService access;

    public record LineInput(long productId, long skuId, int expectedStock, int delta) {}
    public record PreviewLine(long productId, long skuId, String productName, String skuName,
                              long currentStock, int expectedStock, int delta, long afterStock,
                              String conflictCode) {}
    public record Preview(boolean eligible, List<PreviewLine> rows) {}
    public record AppliedLine(long productId, long skuId, long beforeStock, int delta,
                              long afterStock, String reason) {}
    public record BatchResult(String batchId, long shopId, String state,
                              List<AppliedLine> lines, LocalDateTime appliedAt) {}
    public record ResultLookup(String status, BatchResult batch) {}

    private record Normalized(long shopId, List<LineInput> lines, String reason, String hash) {}
    private record Product(long id, long shopId, String name, long aggregateStock) {}
    private record Sku(long productId, long id, String name, long stock) {}
    private record Snapshot(Map<Long, Product> products, Map<String, Sku> skus,
                            Map<Long, Long> skuTotals) {}

    @Transactional(readOnly = true)
    public Preview preview(long shopId, List<LineInput> lines, String reason) {
        Normalized normalized = normalize(shopId, lines, reason, null);
        access.requireShop(shopId);
        Snapshot snapshot = readSnapshot(normalized, false);
        return evaluate(normalized, snapshot);
    }

    public BatchResult apply(long shopId, List<LineInput> lines, String reason, String key) {
        Normalized normalized = normalize(shopId, lines, reason, key);
        long actorId = actorId();

        // Keep the same leading locks as order placement and catalog edits: shop, products by ID.
        lockShop(shopId);
        Optional<BatchResult> replay = existingResult(actorId, key, normalized.hash());
        if (replay.isPresent()) return replay.get();

        Snapshot products = readProducts(normalized, true);
        // Validate every SKU and its initial bounds before the first business write.
        // The locked read below remains authoritative for commit-time stock checks.
        Preview admission = evaluate(normalized, readSkusAndTotals(normalized, products, false));
        if (!admission.eligible()) {
            String conflict = admission.rows().stream().map(PreviewLine::conflictCode)
                    .filter(Objects::nonNull).findFirst().orElse("STOCK_BATCH_CONFLICT");
            throw reject(conflict);
        }

        String batchId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO yshop_product_stock_batch(batch_id,actor_id,request_key,request_hash,shop_id,state,result_json,applied_at) "
                        + "VALUES(?,?,?,?,?,'PENDING',NULL,NULL) ON DUPLICATE KEY UPDATE request_key=request_key",
                batchId, actorId, key, normalized.hash(), shopId);
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT batch_id,request_hash,state,result_json FROM yshop_product_stock_batch "
                        + "WHERE actor_id=? AND request_key=? FOR UPDATE", actorId, key);
        if (!normalized.hash().equals(Objects.toString(head.get("request_hash"), "")))
            throw reject("STOCK_BATCH_IDEMPOTENCY_CONFLICT");
        if (!batchId.equals(Objects.toString(head.get("batch_id"), "")))
            return decodeExisting(head);

        Snapshot snapshot = readSkusAndTotals(normalized, products, true);
        Preview preview = evaluate(normalized, snapshot);
        if (!preview.eligible()) {
            String conflict = preview.rows().stream().map(PreviewLine::conflictCode)
                    .filter(Objects::nonNull).findFirst().orElse("STOCK_BATCH_CONFLICT");
            throw reject(conflict);
        }

        Map<Long, Long> afterTotals = new TreeMap<>(snapshot.skuTotals());
        for (LineInput line : normalized.lines()) {
            Sku sku = snapshot.skus().get(pair(line.productId(), line.skuId()));
            long after = sku.stock() + (long) line.delta();
            if (jdbc.update("UPDATE yshop_store_product_attr_value SET stock=stock+? "
                            + "WHERE product_id=? AND id=? AND stock=?",
                    line.delta(), line.productId(), line.skuId(), sku.stock()) != 1)
                throw reject("STOCK_ADJUSTMENT_CONFLICT");
            afterTotals.compute(line.productId(), (ignored, total) -> total + line.delta());
        }
        for (var entry : afterTotals.entrySet()) {
            if (entry.getValue() < 0 || entry.getValue() > MAX_PRODUCT_STOCK)
                throw reject("STOCK_TOTAL_TOO_LARGE");
            if (jdbc.update("UPDATE yshop_store_product SET stock=? WHERE id=? AND shop_id=? AND deleted=0",
                    entry.getValue(), entry.getKey(), shopId) != 1)
                throw reject("BATCH_STOCK_RESOURCE_UNAVAILABLE");
        }

        List<AppliedLine> applied = new ArrayList<>();
        for (LineInput line : normalized.lines()) {
            Sku sku = snapshot.skus().get(pair(line.productId(), line.skuId()));
            long after = sku.stock() + (long) line.delta();
            insertLineAudit(actorId, batchId, normalized, line, sku.stock(), after);
            applied.add(new AppliedLine(line.productId(), line.skuId(), sku.stock(), line.delta(), after, normalized.reason()));
        }
        LocalDateTime appliedAt = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class)).toLocalDateTime();
        BatchResult result = new BatchResult(batchId, shopId, "APPLIED", List.copyOf(applied), appliedAt);
        if (jdbc.update("UPDATE yshop_product_stock_batch SET state='APPLIED',result_json=?,applied_at=? "
                        + "WHERE batch_id=? AND state='PENDING'",
                JsonUtils.toJsonString(result), Timestamp.valueOf(appliedAt), batchId) != 1)
            throw reject("STOCK_BATCH_RESULT_WRITE_FAILED");
        return result;
    }

    @Transactional(readOnly = true)
    public ResultLookup result(String key) {
        validateKey(key);
        long actorId = actorId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT shop_id,state,result_json FROM yshop_product_stock_batch WHERE actor_id=? AND request_key=?",
                actorId, key);
        if (rows.isEmpty()) return new ResultLookup("UNCONFIRMED", null);
        Map<String, Object> row = rows.get(0);
        long shopId = number(row.get("shop_id"));
        access.requireShop(shopId);
        if (!"APPLIED".equals(Objects.toString(row.get("state"), "")) || row.get("result_json") == null)
            return new ResultLookup("UNCONFIRMED", null);
        BatchResult result = JsonUtils.parseObject(row.get("result_json").toString(), BatchResult.class);
        if (result == null || !"APPLIED".equals(result.state())) throw reject("STOCK_BATCH_RESULT_INVALID");
        return new ResultLookup("CONFIRMED", result);
    }

    private Normalized normalize(long shopId, List<LineInput> input, String rawReason, String key) {
        if (shopId <= 0 || input == null || input.isEmpty() || input.size() > 100)
            throw reject("STOCK_BATCH_SIZE_INVALID");
        if (key != null) validateKey(key);
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty() || reason.length() > 200) throw reject("STOCK_BATCH_REASON_INVALID");
        List<LineInput> lines = new ArrayList<>(input);
        if (lines.stream().anyMatch(Objects::isNull)) throw reject("STOCK_BATCH_LINE_INVALID");
        lines.sort(Comparator.comparingLong(LineInput::productId).thenComparingLong(LineInput::skuId));
        String prior = null;
        for (LineInput line : lines) {
            if (line.productId() <= 0 || line.skuId() <= 0 || line.expectedStock() < 0
                    || line.delta() == 0 || Math.abs((long) line.delta()) > MAX_SKU_STOCK)
                throw reject("STOCK_ADJUSTMENT_INVALID");
            String pair = pair(line.productId(), line.skuId());
            if (pair.equals(prior)) throw reject("STOCK_BATCH_DUPLICATE_SKU");
            prior = pair;
        }
        String canonical = JsonUtils.toJsonString(List.of("AVAILABLE_STOCK_BATCH_V1", shopId, reason,
                lines.stream().map(line -> List.of(line.productId(), line.skuId(), line.expectedStock(), line.delta())).toList()));
        return new Normalized(shopId, List.copyOf(lines), reason, sha256(canonical));
    }

    private Snapshot readSnapshot(Normalized normalized, boolean lockSkus) {
        return readSkusAndTotals(normalized, readProducts(normalized, false), lockSkus);
    }

    private Snapshot readProducts(Normalized normalized, boolean lockProducts) {
        Map<Long, Product> products = new TreeMap<>();
        List<Long> productIds = normalized.lines().stream().map(LineInput::productId).distinct().sorted().toList();
        for (long productId : productIds) {
            access.requireProduct(productId);
            String sql = "SELECT id,shop_id,store_name,stock FROM yshop_store_product "
                    + "WHERE id=? AND deleted=0" + (lockProducts ? " FOR UPDATE" : "");
            List<Map<String, Object>> rows = jdbc.queryForList(sql, productId);
            if (rows.size() != 1 || number(rows.get(0).get("shop_id")) != normalized.shopId())
                throw reject("BATCH_STOCK_RESOURCE_UNAVAILABLE");
            Map<String, Object> row = rows.get(0);
            products.put(productId, new Product(productId, normalized.shopId(),
                    Objects.toString(row.get("store_name"), ""), number(row.get("stock"))));
        }
        return new Snapshot(products, new TreeMap<>(), new TreeMap<>());
    }

    private Snapshot readSkusAndTotals(Normalized normalized, Snapshot products, boolean lockSkus) {
        Map<String, Sku> skus = new TreeMap<>();
        for (LineInput line : normalized.lines()) {
            String sql = "SELECT product_id,id,sku,stock FROM yshop_store_product_attr_value "
                    + "WHERE product_id=? AND id=?" + (lockSkus ? " FOR UPDATE" : "");
            List<Map<String, Object>> rows = jdbc.queryForList(sql, line.productId(), line.skuId());
            if (rows.size() != 1) throw reject("BATCH_STOCK_RESOURCE_UNAVAILABLE");
            Map<String, Object> row = rows.get(0);
            long productId = number(row.get("product_id")), skuId = number(row.get("id"));
            skus.put(pair(productId, skuId), new Sku(productId, skuId,
                    Objects.toString(row.get("sku"), ""), number(row.get("stock"))));
        }
        Map<Long, Long> totals = new TreeMap<>();
        for (long productId : products.products().keySet()) {
            Long total = jdbc.queryForObject("SELECT COALESCE(SUM(stock),0) FROM yshop_store_product_attr_value WHERE product_id=?",
                    Long.class, productId);
            totals.put(productId, Objects.requireNonNull(total));
        }
        return new Snapshot(products.products(), skus, totals);
    }

    private Preview evaluate(Normalized normalized, Snapshot snapshot) {
        Map<Long, Long> deltas = new TreeMap<>();
        for (LineInput line : normalized.lines()) deltas.merge(line.productId(), (long) line.delta(), Long::sum);
        Map<Long, String> productConflicts = new HashMap<>();
        for (long productId : snapshot.products().keySet()) {
            Product product = snapshot.products().get(productId);
            long sum = snapshot.skuTotals().get(productId);
            if (product.aggregateStock() != sum) productConflicts.put(productId, "PRODUCT_STOCK_SUM_MISMATCH");
            long afterTotal = sum + deltas.getOrDefault(productId, 0L);
            if (afterTotal < 0 || afterTotal > MAX_PRODUCT_STOCK)
                productConflicts.put(productId, "STOCK_TOTAL_TOO_LARGE");
        }
        List<PreviewLine> result = new ArrayList<>();
        for (LineInput line : normalized.lines()) {
            Sku sku = snapshot.skus().get(pair(line.productId(), line.skuId()));
            if (sku == null) throw reject("BATCH_STOCK_RESOURCE_UNAVAILABLE");
            long after = sku.stock() + (long) line.delta();
            String conflict = null;
            if (sku.stock() != line.expectedStock()) conflict = "STOCK_CHANGED_REFRESH_REQUIRED";
            else if (after < 0 || after > MAX_SKU_STOCK) conflict = "STOCK_ADJUSTMENT_INVALID";
            else if (productConflicts.containsKey(line.productId())) conflict = productConflicts.get(line.productId());
            Product product = snapshot.products().get(line.productId());
            result.add(new PreviewLine(line.productId(), line.skuId(), product.name(), sku.name(), sku.stock(),
                    line.expectedStock(), line.delta(), after, conflict));
        }
        boolean eligible = result.stream().noneMatch(row -> row.conflictCode() != null);
        return new Preview(eligible, List.copyOf(result));
    }

    private void lockShop(long shopId) {
        access.requireShop(shopId);
        List<Long> rows = jdbc.queryForList("SELECT id FROM yshop_store_shop WHERE id=? AND deleted=0 FOR UPDATE", Long.class, shopId);
        if (rows.size() != 1) throw reject("STORE_CONTEXT_REQUIRED");
    }

    private Optional<BatchResult> existingResult(long actorId, String key, String requestHash) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT batch_id,request_hash,state,result_json FROM yshop_product_stock_batch WHERE actor_id=? AND request_key=?",
                actorId, key);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        if (!requestHash.equals(Objects.toString(row.get("request_hash"), "")))
            throw reject("STOCK_BATCH_IDEMPOTENCY_CONFLICT");
        return Optional.of(decodeExisting(row));
    }

    private BatchResult decodeExisting(Map<String, Object> row) {
        if (!"APPLIED".equals(Objects.toString(row.get("state"), "")) || row.get("result_json") == null)
            throw reject("STOCK_BATCH_UNCONFIRMED");
        BatchResult result = JsonUtils.parseObject(row.get("result_json").toString(), BatchResult.class);
        if (result == null || !"APPLIED".equals(result.state())
                || !Objects.toString(row.get("batch_id"), "").equals(result.batchId()))
            throw reject("STOCK_BATCH_RESULT_INVALID");
        return result;
    }

    private void insertLineAudit(long actorId, String batchId, Normalized normalized,
                                 LineInput line, long before, long after) {
        String rowKey = "b_" + sha256(batchId + ":" + line.productId() + ":" + line.skuId()).substring(0, 62);
        String rowHash = sha256(JsonUtils.toJsonString(List.of("STOCK_BATCH_LINE_V1", batchId,
                normalized.shopId(), line.productId(), line.skuId(), before, line.delta(), after, normalized.reason())));
        try {
            jdbc.update("INSERT INTO yshop_product_operation(operation_id,actor_id,request_key,request_hash,product_id,sku_id,kind,before_value,after_value,reason,batch_id,delta_value) "
                            + "VALUES(?,?,?,?,?,?,'STOCK',?,?,?,?,?)",
                    UUID.randomUUID().toString().replace("-", ""), actorId, rowKey, rowHash,
                    line.productId(), line.skuId(), before, after, normalized.reason(), batchId, line.delta());
        } catch (org.springframework.dao.DuplicateKeyException collision) {
            throw reject("STOCK_BATCH_AUDIT_KEY_CONFLICT");
        }
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,64}")) throw reject("STOCK_BATCH_KEY_INVALID");
    }

    private static long actorId() {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) throw reject("CATALOG_ACTOR_REQUIRED");
        return actor;
    }

    private static String pair(long productId, long skuId) { return productId + ":" + skuId; }
    private static long number(Object value) {
        if (!(value instanceof Number number)) throw reject("BATCH_STOCK_DATABASE_VALUE_INVALID");
        return number.longValue();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("STOCK_BATCH_HASH_FAILED", error);
        }
    }
}
