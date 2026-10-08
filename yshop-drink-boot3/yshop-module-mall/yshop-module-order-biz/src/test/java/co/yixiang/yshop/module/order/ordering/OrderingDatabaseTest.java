package co.yixiang.yshop.module.order.ordering;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.module.order.controller.app.order.param.AppOrderParam;
import co.yixiang.yshop.module.order.service.ordering.OrderPlacementService;
import co.yixiang.yshop.module.order.service.storeorder.AppStoreOrderServiceImpl;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import co.yixiang.yshop.module.system.api.permission.PermissionApi;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

class OrderingDatabaseTest {
    JdbcTemplate db;
    DataSource data;
    DataSourceTransactionManager tm;
    OrderPlacementService orders;
    boolean mysql;
    static final List<String> TABLES =
            List.of(
                    "yshop_store_shop",
                    "yshop_store_product_category",
                    "yshop_store_product",
                    "yshop_store_product_attr_value",
                    "yshop_coupon_user",
                    "yshop_user",
                    "yshop_user_address",
                    "yshop_store_order",
                    "yshop_order_number",
                    "yshop_store_order_cart_info",
                    "yshop_store_order_status",
                    "yshop_order_submission",
                    "yshop_order_inventory_reservation");
    Path boot;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource();
        String config = System.getenv("YSHOP_ORDERING_ACCEPTANCE_CONFIG");
        mysql = config != null;
        if (mysql) {
            var props = new Properties();
            try (var in = Files.newInputStream(Path.of(config))) {
                props.load(in);
            }
            assertTrue(
                    props.getProperty("url")
                            .matches(
                                    "jdbc:mysql://127[.]0[.]0[.]1:3306/yshop_acceptance_phase6a_[a-f0-9]{8}[?].*"));
            assertTrue(props.getProperty("username").matches("accept6a_[a-f0-9]{8}"));
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            ds.setUrl(props.getProperty("url"));
            ds.setUsername(props.getProperty("username"));
            ds.setPassword(props.getProperty("password"));
        } else {
            ds.setDriverClassName("org.h2.Driver");
            ds.setUrl(
                    "jdbc:h2:mem:ordering_"
                            + UUID.randomUUID()
                            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000");
            ds.setUsername("sa");
        }
        data = ds;
        db = new JdbcTemplate(ds);
        tm = new DataSourceTransactionManager(ds);
        orders =
                new OrderPlacementService(
                        db,
                        tm,
                        new co.yixiang.yshop.module.order.service.payment.attempt
                                .PaymentCancellationGuard(db));
        ReflectionTestUtils.setField(
                orders,
                "clock",
                Clock.fixed(Instant.parse("2026-10-08T04:00:00Z"), ZoneId.of("Asia/Shanghai")));
        boot = Path.of("").toAbsolutePath();
        while (!Files.exists(boot.resolve("sql/yixiang-drink-open.sql"))) boot = boot.getParent();
        for (String t : TABLES) db.execute("DROP TABLE IF EXISTS " + t);
        if (mysql) {
            String seed = Files.readString(boot.resolve("sql/yixiang-drink-open.sql"));
            for (String t : TABLES.subList(0, 11)) {
                var m =
                        java.util.regex.Pattern.compile(
                                        "CREATE TABLE `" + t + "`.*?;",
                                        java.util.regex.Pattern.DOTALL)
                                .matcher(seed);
                assertTrue(m.find());
                db.execute(m.group());
            }
            migrate();
        } else {
            for (String ddl :
                    Files.readString(
                                    boot.resolve(
                                            "yshop-module-mall/yshop-module-order-biz/src/test/resources/ordering-h2.sql"))
                            .split(";")) if (!ddl.isBlank()) db.execute(ddl);
        }
        for (String table :
                List.of(
                        "yshop_order_payment_attempt",
                        "yshop_order_payment",
                        "yshop_order_payment_conflict"))
            db.execute("DROP TABLE IF EXISTS " + table);
        db.execute(
                "CREATE TABLE yshop_order_payment_attempt(attempt_id VARCHAR(32) PRIMARY"
                    + " KEY,order_id VARCHAR(64),status VARCHAR(32),provider"
                    + " VARCHAR(16),prepay_requested_at TIMESTAMP,prepay_reference"
                    + " VARCHAR(128),provider_transaction_id VARCHAR(128),payment_event_id"
                    + " VARCHAR(32),paid_at TIMESTAMP,reconciliation_token"
                    + " VARCHAR(32),reconciliation_lease_until TIMESTAMP,remote_terminal_state"
                    + " VARCHAR(16),remote_confirmed_at TIMESTAMP)");
        db.execute("CREATE TABLE yshop_order_payment(order_id VARCHAR(64))");
        db.execute("CREATE TABLE yshop_order_payment_conflict(claimed_order_id VARCHAR(64))");
        for(String column:List.of("template_version BIGINT DEFAULT 0","redeemed_order_id VARCHAR(32)","redeemed_at TIMESTAMP","invalid_reason VARCHAR(200)")) db.execute("ALTER TABLE yshop_coupon_user ADD COLUMN "+column);
        db.execute("DROP TABLE IF EXISTS yshop_coupon_operation");
        db.execute("CREATE TABLE yshop_coupon_operation(event_key VARCHAR(96) PRIMARY KEY,coupon_id BIGINT,coupon_user_id BIGINT,order_id VARCHAR(32),actor_id BIGINT,actor_type VARCHAR(16),kind VARCHAR(24),reason VARCHAR(200),create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        fixtures();
    }

    void migrate() throws Exception {
        String sql =
                Files.readString(boot.resolve("sql/migrations/2026-10-08-multistore-ordering.sql"));
        // PREPARE/EXECUTE is session-local: keep the entire migration on one physical connection.
        new TransactionTemplate(tm)
                .executeWithoutResult(
                        tx -> {
                            for (String part : sql.split(";")) {
                                if (!part.isBlank()) db.execute(part);
                            }
                        });
    }

    void fixtures() {
        for (int i = 1; i <= 2; i++) {
            db.update(
                    "INSERT INTO"
                        + " yshop_store_shop(id,name,mobile,images,address,address_map,start_time,end_time,lng,lat,status,admin_id)"
                        + " VALUES(?,?,'','[]','','','2026-10-08 00:00:00','2026-10-08"
                        + " 00:00:00','0','0',1,?)",
                    i,
                    "Synthetic shop " + i,
                    "10" + i + ",103");
            db.update(
                    "INSERT INTO"
                        + " yshop_store_product_category(id,shop_id,parent_id,name,pic_url,status)"
                        + " VALUES(?,?,0,?,'',0)",
                    i,
                    i,
                    "Category " + i);
            db.update(
                    "INSERT INTO"
                        + " yshop_store_product(id,shop_id,image,slider_image,store_name,store_info,keyword,cate_id,stock,sales,is_show,is_integral)"
                        + " VALUES(?,?,'','','Synthetic item','','',?,10,0,1,0)",
                    i,
                    i,
                    Integer.toString(i));
            db.update(
                    "INSERT INTO"
                        + " yshop_store_product_attr_value(id,product_id,sku,stock,sales,price,cost)"
                        + " VALUES(?,?,?,10,0,1.23,0)",
                    i,
                    i,
                    "SKU" + i);
            db.update(
                    "INSERT INTO yshop_user(id,username,password,nickname) VALUES(?,?,'','Synthetic"
                            + " member')",
                    i,
                    "synthetic-user-" + i);
        }
        db.update(
                "INSERT INTO"
                    + " yshop_coupon_user(id,shop_id,title,least,`value`,start_time,end_time,type,user_id,status,coupon_id)"
                    + " VALUES(1,'1','Synthetic coupon',0,0.10,'2026-10-01 00:00:00','2026-11-01"
                    + " 00:00:00',0,1,0,1)");
    }

    AppOrderParam request() {
        var p = new AppOrderParam();
        p.setShopId("1");
        p.setOrderType("takein");
        p.setGettime(0);
        p.setProductId(List.of("1"));
        p.setSpec(List.of("SKU1"));
        p.setNumber(List.of("1"));
        p.setCouponId("0");
        p.setIdempotencyKey(UUID.randomUUID().toString());
        return p;
    }

    String place(AppOrderParam p) {
        return orders.place(1L, p).get("orderId").toString();
    }

    long count(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    void rejected(AppOrderParam p) {
        assertThrows(RuntimeException.class, () -> place(p));
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_store_order"));
        assertEquals(10, count("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_order_submission"));
    }

    List<Boolean> parallel(int size, java.util.concurrent.Callable<Boolean> action)
            throws Exception {
        var pool = Executors.newFixedThreadPool(size);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < size; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return action.call();
                                }));
            start.countDown();
            List<Boolean> result = new ArrayList<>();
            for (var f : futures) result.add(f.get(45, TimeUnit.SECONDS));
            return result;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void correctStoreServerPriceAndSynchronousSnapshot() {
        String id = place(request());
        assertEquals(1, count("SELECT shop_id FROM yshop_store_order WHERE order_id=?", id));
        assertEquals(
                new java.math.BigDecimal("1.23"),
                db.queryForObject(
                        "SELECT pay_price FROM yshop_store_order WHERE order_id=?",
                        java.math.BigDecimal.class,
                        id));
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE order_id=?", id));
        assertEquals(
                1, count("SELECT COUNT(*) FROM yshop_store_order_cart_info WHERE order_id=?", id));
        assertEquals(9, count("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(10, count("SELECT stock FROM yshop_store_product WHERE id=2"));
    }

    @Test
    void crossStoreProductRejected() {
        var p = request();
        p.setShopId("2");
        rejected(p);
    }

    @Test
    void crossProductSkuRejected() {
        var p = request();
        p.setSpec(List.of("SKU2"));
        rejected(p);
    }

    @Test
    void mixedStoresRejectedAtomically() {
        var p = request();
        p.setProductId(List.of("1", "2"));
        p.setSpec(List.of("SKU1", "SKU2"));
        p.setNumber(List.of("1", "1"));
        rejected(p);
    }

    @Test
    void forgedPriceIgnored() throws Exception {
        var mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .FAIL_ON_UNKNOWN_PROPERTIES,
                                false);
        var p =
                mapper.readValue(
                        "{\"shopId\":\"1\",\"orderType\":\"takein\",\"productId\":[\"1\"],\"spec\":[\"SKU1\"],\"number\":[\"1\"],\"price\":0.01,\"amount\":0,\"idempotencyKey\":\"synthetic_price_ignored\"}",
                        AppOrderParam.class);
        String id = place(p);
        assertEquals(
                new java.math.BigDecimal("1.23"),
                db.queryForObject(
                        "SELECT pay_price FROM yshop_store_order WHERE order_id=?",
                        java.math.BigDecimal.class,
                        id));
    }

    @Test
    void unavailableStore() {
        db.update("UPDATE yshop_store_shop SET status=0 WHERE id=1");
        rejected(request());
    }

    @Test
    void outsideHours() {
        db.update(
                "UPDATE yshop_store_shop SET start_time='2026-10-08 13:00:00',end_time='2026-10-08"
                        + " 14:00:00' WHERE id=1");
        rejected(request());
    }

    @Test
    void overnightHours() {
        db.update(
                "UPDATE yshop_store_shop SET start_time='2026-10-08 20:00:00',end_time='2026-10-08"
                        + " 14:00:00' WHERE id=1");
        assertNotNull(place(request()));
    }

    @Test
    void missingStore() {
        var p = request();
        p.setShopId("999");
        rejected(p);
    }

    @Test
    void hiddenProduct() {
        db.update("UPDATE yshop_store_product SET is_show=0 WHERE id=1");
        rejected(request());
    }

    @Test
    void wrongCategoryStore() {
        db.update("UPDATE yshop_store_product SET cate_id='2' WHERE id=1");
        rejected(request());
    }

    @Test
    void disabledCategory() {
        db.update("UPDATE yshop_store_product_category SET status=1 WHERE id=1");
        rejected(request());
    }

    @Test
    void negativeQuantity() {
        var p = request();
        p.setNumber(List.of("-1"));
        rejected(p);
    }

    @Test
    void zeroQuantity() {
        var p = request();
        p.setNumber(List.of("0"));
        rejected(p);
    }

    @Test
    void fractionalQuantity() {
        var p = request();
        p.setNumber(List.of("1.5"));
        rejected(p);
    }

    @Test
    void excessiveQuantity() {
        var p = request();
        p.setNumber(List.of("1000"));
        rejected(p);
    }

    @Test
    void missingSku() {
        var p = request();
        p.setSpec(List.of("unknown"));
        rejected(p);
    }

    @Test
    void malformedLines() {
        var p = request();
        p.setNumber(List.of());
        rejected(p);
    }

    @Test
    void missingSubmissionKey() {
        var p = request();
        p.setIdempotencyKey(null);
        rejected(p);
    }

    @Test
    void skuSoldOut() {
        db.update("UPDATE yshop_store_product_attr_value SET stock=0 WHERE id=1");
        rejected(request());
    }

    @Test
    void aggregateSoldOut() {
        db.update("UPDATE yshop_store_product SET stock=0 WHERE id=1");
        assertThrows(RuntimeException.class, () -> place(request()));
        assertEquals(10, count("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_store_order"));
    }

    @Test
    void redisFailureCannotSkipInventory() {
        var app = new AppStoreOrderServiceImpl();
        var redis = mock(org.redisson.api.RedissonClient.class);
        when(redis.getLock(anyString()))
                .thenThrow(new IllegalStateException("synthetic Redis failure"));
        ReflectionTestUtils.setField(app, "redissonClient", redis);
        ReflectionTestUtils.setField(app, "orderPlacementService", orders);
        app.createOrder(1L, request());
        assertEquals(9, count("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        verifyNoInteractions(redis);
    }

    @Test
    void duplicateKeySameRequest() {
        var p = request();
        String first = place(p);
        assertEquals(first, place(p));
        assertEquals(1, count("SELECT COUNT(*) FROM yshop_store_order"));
        assertEquals(9, count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    @Test
    void duplicateKeyConflict() {
        var p = request();
        place(p);
        p.setNumber(List.of("2"));
        assertThrows(RuntimeException.class, () -> place(p));
        assertEquals(9, count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    @Test
    void duplicateKeyTwentyConcurrent() throws Exception {
        var p = request();
        var ids = ConcurrentHashMap.<String>newKeySet();
        parallel(
                20,
                () -> {
                    ids.add(place(p));
                    return true;
                });
        assertEquals(1, ids.size());
        assertEquals(1, count("SELECT COUNT(*) FROM yshop_store_order"));
        assertEquals(9, count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    @RepeatedTest(20)
    void lastItemTwentyBuyers() throws Exception {
        db.update("UPDATE yshop_store_product SET stock=1 WHERE id=1");
        db.update("UPDATE yshop_store_product_attr_value SET stock=1 WHERE id=1");
        var results =
                parallel(
                        20,
                        () -> {
                            try {
                                place(request());
                                return true;
                            } catch (RuntimeException expected) {
                                return false;
                            }
                        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, count("SELECT COUNT(*) FROM yshop_store_order"));
        assertEquals(0, count("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(0, count("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(10, count("SELECT stock FROM yshop_store_product WHERE id=2"));
    }

    @Test
    void snapshotFailureRollsBackEverything() {
        db.execute(
                "ALTER TABLE yshop_store_order_cart_info ADD CONSTRAINT injected_failure"
                        + " CHECK(number<0)");
        var p = request();
        p.setCouponId("1");
        rejected(p);
        assertEquals(0, count("SELECT status FROM yshop_coupon_user WHERE id=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM yshop_order_inventory_reservation"));
    }

    @Test
    void couponOtherOwner() {
        db.update("UPDATE yshop_coupon_user SET user_id=2 WHERE id=1");
        var p = request();
        p.setCouponId("1");
        rejected(p);
    }

    @Test
    void couponOtherStore() {
        db.update("UPDATE yshop_coupon_user SET shop_id='2' WHERE id=1");
        var p = request();
        p.setCouponId("1");
        rejected(p);
    }

    @Test
    void couponMinimum() {
        db.update("UPDATE yshop_coupon_user SET least=10 WHERE id=1");
        var p = request();
        p.setCouponId("1");
        rejected(p);
    }

    @Test
    void couponExpired() {
        db.update("UPDATE yshop_coupon_user SET end_time='2026-10-07 00:00:00' WHERE id=1");
        var p = request();
        p.setCouponId("1");
        rejected(p);
    }

    @Test
    void couponWrongOrderType() {
        db.update("UPDATE yshop_coupon_user SET type=2 WHERE id=1");
        var p = request();
        p.setCouponId("1");
        rejected(p);
    }

    @Test
    void couponReserveAndRestore() {
        var p = request();
        p.setCouponId("1");
        String id = place(p);
        assertEquals(
                id,
                db.queryForObject(
                        "SELECT reserved_order_id FROM yshop_coupon_user WHERE id=1",
                        String.class));
        assertEquals(1, count("SELECT status FROM yshop_coupon_user WHERE id=1"));
        orders.cancel(id, 1L, false);
        assertEquals(0, count("SELECT status FROM yshop_coupon_user WHERE id=1"));
        assertNull(
                db.queryForObject(
                        "SELECT reserved_order_id FROM yshop_coupon_user WHERE id=1",
                        String.class));
    }

    @Test
    void couponTwentyConcurrentOnlyOneReservation() throws Exception {
        var results =
                parallel(
                        20,
                        () -> {
                            var p = request();
                            p.setCouponId("1");
                            try {
                                place(p);
                                return true;
                            } catch (RuntimeException ex) {
                                return false;
                            }
                        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, count("SELECT COUNT(*) FROM yshop_store_order"));
    }

    @Test
    void cancellationTwentyExactlyOnce() throws Exception {
        String id = place(request());
        parallel(
                20,
                () -> {
                    orders.cancel(id, 1L, false);
                    return true;
                });
        assertEquals(10, count("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(0, count("SELECT sales FROM yshop_store_product WHERE id=1"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_store_order_status WHERE"
                                + " change_type='cancel_order'"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_order_inventory_reservation WHERE released_at"
                                + " IS NOT NULL"));
    }

    @Test
    void cancellationOwnerCheckedBeforeIdempotentResult() {
        String id = place(request());
        assertThrows(RuntimeException.class, () -> orders.cancel(id, 2L, false));
        orders.cancel(id, 1L, false);
        assertThrows(RuntimeException.class, () -> orders.cancel(id, 2L, false));
        assertEquals(10, count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    @Test
    void cancelCouponFailureRollsBackStockAndMarker() {
        var p = request();
        p.setCouponId("1");
        String id = place(p);
        db.execute("ALTER TABLE yshop_coupon_user ADD CONSTRAINT injected_failure CHECK(status=1)");
        assertThrows(RuntimeException.class, () -> orders.cancel(id, 1L, false));
        assertEquals(9, count("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(0, count("SELECT deleted FROM yshop_store_order"));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM yshop_order_inventory_reservation WHERE released_at"
                                + " IS NOT NULL"));
    }

    @Test
    void timeoutCannotCancelFreshOrder() {
        String id = place(request());
        assertThrows(RuntimeException.class, () -> orders.cancel(id, null, true));
        assertEquals(9, count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    @Test
    void timeoutAndCustomerRaceExactlyOnce() throws Exception {
        String id = place(request());
        db.update(
                "UPDATE yshop_store_order SET create_time='2026-10-08 11:00:00' WHERE order_id=?",
                id);
        parallel(
                20,
                () -> {
                    orders.cancel(id, 1L, ThreadLocalRandom.current().nextBoolean());
                    return true;
                });
        assertEquals(10, count("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM yshop_store_order_status WHERE"
                                + " change_type='cancel_order'"));
    }

    @Test
    void databaseRecoveryWithoutRedis() {
        String id = place(request());
        db.update(
                "UPDATE yshop_store_order SET create_time='2026-10-08 11:00:00' WHERE order_id=?",
                id);
        assertEquals(1, orders.expireBatch());
        assertEquals(0, orders.expireBatch());
        assertEquals(10, count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    @Test
    void retryAfterCanceledReturnsSameOrder() {
        var p = request();
        String id = place(p);
        orders.cancel(id, 1L, false);
        assertEquals(id, place(p));
        assertEquals(10, count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    StoreAccessService access(long uid, boolean hq) {
        var user = new LoginUser().setId(uid).setUserType(2);
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        var permission = mock(PermissionApi.class);
        when(permission.hasAnyRoles(eq(uid), any(String[].class))).thenReturn(hq);
        return new StoreAccessService(db, permission);
    }

    @Test
    void staffCannotReadOrWriteOtherStore() {
        var access = access(101, false);
        access.requireShop(1L);
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> access.requireShop(2L));
        String id = place(request());
        Long oid =
                db.queryForObject(
                        "SELECT id FROM yshop_store_order WHERE order_id=?", Long.class, id);
        access(102, false);
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> new StoreAccessService(db, mock(PermissionApi.class)).requireOrder(oid));
    }

    @Test
    void multiShopManagerAndFreshRevocation() {
        var access = access(103, false);
        assertEquals(Set.of(1L, 2L), access.allowedShopIds());
        db.update("UPDATE yshop_store_shop SET admin_id='102' WHERE id=2");
        assertEquals(Set.of(1L), access.allowedShopIds());
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> access.requireShop(2L));
    }

    @Test
    void headquartersMustHaveExplicitRole() {
        var access = access(999, true);
        assertNull(access.allowedShopIds());
        access.requireShop(1L);
        access.requireShop(2L);
    }

    @Test
    void unassignedAccountIsNotImplicitHeadquarters() {
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> access(999, false).allowedShopIds());
    }

    @Test
    void memberCannotUseStaffScope() {
        var access = access(1, true);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new LoginUser().setId(1L).setUserType(1), null, List.of()));
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                access::allowedShopIds);
    }

    @Test
    void anonymousStaffScopeDenied() {
        var access = access(1, true);
        SecurityContextHolder.clearContext();
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                access::allowedShopIds);
    }

    @Test
    void migrationRerunPreservesUnpaidOrder() throws Exception {
        String id = place(request());
        if (mysql) {
            migrate();
            migrate();
        }
        assertEquals(
                1, count("SELECT COUNT(*) FROM yshop_store_order WHERE order_id=? AND paid=0", id));
        assertEquals(9, count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    @Test
    void databaseLockFailureRollsBackSubmissionAndInventory() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        var holder =
                pool.submit(
                        () -> {
                            try (var c = data.getConnection()) {
                                c.setAutoCommit(false);
                                try (var q =
                                        c.prepareStatement(
                                                "SELECT id FROM yshop_store_shop WHERE id=1 FOR"
                                                        + " UPDATE")) {
                                    q.executeQuery().close();
                                }
                                held.countDown();
                                release.await(15, TimeUnit.SECONDS);
                                c.rollback();
                            }
                            return true;
                        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            rejected(request());
            assertEquals(0, count("SELECT COUNT(*) FROM yshop_order_inventory_reservation"));
        } finally {
            release.countDown();
            assertTrue(holder.get(5, TimeUnit.SECONDS));
            pool.shutdownNow();
        }
    }

    @Test
    void disabledParentCategoryRejected() {
        db.update(
                "INSERT INTO yshop_store_product_category(id,shop_id,parent_id,name,pic_url,status)"
                        + " VALUES(3,1,0,'Disabled parent','',1)");
        db.update("UPDATE yshop_store_product_category SET parent_id=3 WHERE id=1");
        rejected(request());
    }

    @Test
    void cyclicCategoryRejected() {
        db.update("UPDATE yshop_store_product_category SET parent_id=1 WHERE id=1");
        rejected(request());
    }

    @Test
    void otherStoreParentCategoryRejected() {
        db.update("UPDATE yshop_store_product_category SET parent_id=2 WHERE id=1");
        rejected(request());
    }

    @Test
    void customerReadUsesOwnedGroupedReferences() throws Exception {
        String id = place(request());
        if (!mysql) {
            db.execute("ALTER TABLE yshop_store_order ADD COLUMN `unique` VARCHAR(64)");
            db.execute("ALTER TABLE yshop_store_order ADD COLUMN extend_order_id VARCHAR(64)");
        }
        db.update(
                "UPDATE yshop_store_order SET"
                        + " `unique`='synthetic-unique',extend_order_id='synthetic-extended' WHERE"
                        + " order_id=?",
                id);
        // Run the actual application's generated predicate against the database, not an ownership
        // mock.
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(config, "ordering"),
                co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO.class);
        var mapper =
                mock(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class);
        when(mapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(
                        call -> {
                            var w =
                                    (com.baomidou.mybatisplus.core.conditions.query
                                                            .LambdaQueryWrapper<
                                                    ?>)
                                            call.getArgument(0);
                            var pattern =
                                    java.util.regex.Pattern.compile(
                                            "#\\{ew[.]paramNameValuePairs[.]([^}]+)}");
                            var m = pattern.matcher(w.getSqlSegment());
                            var args = new ArrayList<Object>();
                            var sql = new StringBuffer();
                            while (m.find()) {
                                args.add(w.getParamNameValuePairs().get(m.group(1)));
                                m.appendReplacement(sql, "?");
                            }
                            m.appendTail(sql);
                            var rows =
                                    db.queryForList(
                                            "SELECT id,order_id,uid FROM yshop_store_order WHERE"
                                                    + " deleted=0 AND "
                                                    + sql,
                                            args.toArray());
                            if (rows.isEmpty()) return null;
                            var row = rows.get(0);
                            return co.yixiang.yshop.module.order.dal.dataobject.storeorder
                                    .StoreOrderDO.builder()
                                    .id(((Number) row.get("id")).longValue())
                                    .orderId(row.get("order_id").toString())
                                    .uid(((Number) row.get("uid")).longValue())
                                    .build();
                        });
        var app = new AppStoreOrderServiceImpl();
        ReflectionTestUtils.setField(app, "storeOrderMapper", mapper);
        for (String ref : List.of(id, "synthetic-unique", "synthetic-extended")) {
            assertNotNull(app.getOrderInfo(ref, 1L));
            assertNull(app.getOrderInfo(ref, 2L));
        }
    }

    @Test
    void staffEndpointsRejectCrossStoreBeforeBusinessCalls() {
        String id = place(request());
        Long oid =
                db.queryForObject(
                        "SELECT id FROM yshop_store_order WHERE order_id=?", Long.class, id);
        var access = access(102, false);
        var controller =
                new co.yixiang.yshop.module.order.controller.admin.storeorder
                        .StoreOrderController();
        var service =
                mock(co.yixiang.yshop.module.order.service.storeorder.StoreOrderService.class);
        ReflectionTestUtils.setField(controller, "storeAccess", access);
        ReflectionTestUtils.setField(controller, "storeOrderService", service);
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.getStoreOrder(oid));
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.deleteStoreOrder(oid));
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.takeStoreOrder(oid));
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.payStoreOrder(oid));
        var update =
                new co.yixiang.yshop.module.order.controller.admin.storeorder.vo
                        .StoreOrderUpdateReqVO();
        update.setId(oid);
        update.setShopId(2L);
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.updateStoreOrder(update));
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.getStoreOrderRecordList(oid));
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.getStoreOrderList(List.of(oid)));
        var refund =
                new co.yixiang.yshop.module.order.controller.admin.storeorder.vo
                        .StoreOrderRefundVO();
        refund.setId(oid);
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.refund(refund));
        verifyNoInteractions(service);
    }

    @Test
    void catalogRequiresSelectedStoreAndPublishedOwnership() {
        var catalog =
                new co.yixiang.yshop.module.product.service.storeproduct
                        .AppStoreProductServiceImpl();
        ReflectionTestUtils.setField(catalog, "orderingJdbc", db);
        assertThrows(
                IllegalArgumentException.class, () -> catalog.requireCatalogContext(null, null));
        assertThrows(IllegalArgumentException.class, () -> catalog.requireCatalogContext(1, 2L));
        catalog.requireCatalogContext(1, 1L);
        catalog.requireCatalogContext(2, 2L);
        db.update("UPDATE yshop_store_product SET is_show=0 WHERE id=1");
        assertThrows(IllegalArgumentException.class, () -> catalog.requireCatalogContext(1, 1L));
    }

    @Test
    void productEditCannotDestroyUnpaidSkuReservations() {
        String id = place(request());
        var access = access(101, false);
        var tx = new TransactionTemplate(tm);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> tx.executeWithoutResult(status -> access.requireProductEditable(1L)));
        orders.cancel(id, 1L, false);
        tx.executeWithoutResult(status -> access.requireProductEditable(1L));
    }

    @Test
    void globalAndExplicitMultiStoreCouponAllowed() {
        db.update("UPDATE yshop_coupon_user SET shop_id='0' WHERE id=1");
        var p = request();
        p.setCouponId("1");
        String id = place(p);
        orders.cancel(id, 1L, false);
        db.update("UPDATE yshop_coupon_user SET shop_id='1,2' WHERE id=1");
        p.setIdempotencyKey(UUID.randomUUID().toString());
        assertNotNull(place(p));
    }

    @Test
    void notYetValidCouponRejected() {
        db.update("UPDATE yshop_coupon_user SET start_time='2026-10-09 00:00:00' WHERE id=1");
        var p = request();
        p.setCouponId("1");
        rejected(p);
    }

    @Test
    void usedCouponCannotBeReservedAgain() {
        db.update("UPDATE yshop_coupon_user SET status=1 WHERE id=1");
        var p = request();
        p.setCouponId("1");
        rejected(p);
    }

    @Test
    void ordinaryAdminEditCannotOverwriteOrderFinancialOrOwnershipFields() {
        String id = place(request());
        Long oid =
                db.queryForObject(
                        "SELECT id FROM yshop_store_order WHERE order_id=?", Long.class, id);
        var mapper =
                mock(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class);
        var original =
                co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO.builder()
                        .id(oid)
                        .orderId(id)
                        .uid(1L)
                        .shopId(1L)
                        .paid(0)
                        .orderType("takein")
                        .build();
        when(mapper.selectById(oid)).thenReturn(original);
        var service = new co.yixiang.yshop.module.order.service.storeorder.StoreOrderServiceImpl();
        ReflectionTestUtils.setField(service, "storeOrderMapper", mapper);
        var input =
                new co.yixiang.yshop.module.order.controller.admin.storeorder.vo
                        .StoreOrderUpdateReqVO();
        input.setId(oid);
        input.setShopId(2L);
        input.setUid(2L);
        input.setPaid(1);
        input.setPayPrice(java.math.BigDecimal.ZERO);
        input.setStatus(3);
        input.setOrderId("forged-reference");
        input.setRemark("Allowed note");
        service.updateStoreOrder(input);
        var update =
                org.mockito.ArgumentCaptor.forClass(
                        co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO.class);
        verify(mapper).updateById(update.capture());
        var safe = update.getValue();
        assertEquals(1L, safe.getUid());
        assertEquals(id, safe.getOrderId());
        assertNull(safe.getShopId());
        assertNull(safe.getPaid());
        assertNull(safe.getPayPrice());
        assertNull(safe.getStatus());
        assertEquals("Allowed note", safe.getRemark());
        assertEquals(0, count("SELECT paid FROM yshop_store_order WHERE id=?", oid));
    }

    @Test
    void couponCountScopesGlobalSharedOwnerAndReservation() {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(config, "ordering-coupon"),
                co.yixiang.yshop.module.coupon.dal.dataobject.couponuser.CouponUserDO.class);
        var users =
                mock(co.yixiang.yshop.module.coupon.service.couponuser.AppCouponUserService.class);
        when(users.count(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(
                        call -> {
                            var w =
                                    (com.baomidou.mybatisplus.core.conditions.query
                                                            .LambdaQueryWrapper<
                                                    ?>)
                                            call.getArgument(0);
                            var matcher =
                                    java.util.regex.Pattern.compile(
                                                    "#\\{ew[.]paramNameValuePairs[.]([^}]+)}")
                                            .matcher(w.getSqlSegment());
                            var args = new ArrayList<Object>();
                            var sql = new StringBuffer();
                            while (matcher.find()) {
                                args.add(w.getParamNameValuePairs().get(matcher.group(1)));
                                matcher.appendReplacement(sql, "?");
                            }
                            matcher.appendTail(sql);
                            return count(
                                    "SELECT COUNT(*) FROM yshop_coupon_user WHERE deleted=0 AND "
                                            + sql,
                                    args.toArray());
                        });
        var controller =
                new co.yixiang.yshop.module.coupon.controller.app.coupon.AppCouponController(
                        users,
                        mock(co.yixiang.yshop.module.coupon.service.coupon.AppCouponService.class));
        access(1, false);
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        db.update(
                "UPDATE yshop_coupon_user SET start_time=?,end_time=?,shop_id='0'",
                now.minusDays(1),
                now.plusDays(1));
        assertEquals(1L, controller.getCount(1, 1).getData());
        assertEquals(1L, controller.getCount(2, 2).getData());
        db.update("UPDATE yshop_coupon_user SET shop_id='1,2'");
        assertEquals(1L, controller.getCount(2, 1).getData());
        assertEquals(0L, controller.getCount(3, 1).getData());
        db.update(
                "UPDATE yshop_coupon_user SET reserved_order_id='synthetic-reservation',status=1");
        assertEquals(0L, controller.getCount(1, 1).getData());
        db.update("UPDATE yshop_coupon_user SET reserved_order_id=NULL,status=0");
        access(2, false);
        assertEquals(0L, controller.getCount(1, 1).getData());
    }
}
