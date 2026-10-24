package co.yixiang.yshop.module.order.statistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.order.service.statistics.StoreOrderStatisticsService;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import co.yixiang.yshop.module.system.api.permission.PermissionApi;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class StoreOrderStatisticsDatabaseTest {
    private JdbcTemplate jdbc;
    private StoreOrderStatisticsService service;
    private PermissionApi permissions;
    private final Set<Long> headquartersUsers = new HashSet<>();
    private final long shopOne = 910_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000);
    private final long shopTwo = shopOne + 1;
    private final long orderBase = 900_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000);
    private int nextOrder;
    private int nextShop;
    private boolean mysql;
    private Path boot;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        String config = System.getenv("YSHOP_ORDERING_ACCEPTANCE_CONFIG");
        mysql = config != null;
        if (mysql) {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(Path.of(config))) { properties.load(input); }
            assertTrue(properties.getProperty("url").matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]{2,5}/yshop_acceptance_phase6a_[a-f0-9]{8}[?].*"));
            source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            source.setUrl(properties.getProperty("url"));
            source.setUsername(properties.getProperty("username"));
            source.setPassword(properties.getProperty("password"));
        } else {
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:store_stats_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000");
            source.setUsername("sa");
        }
        jdbc = new JdbcTemplate(source);
        boot = Path.of("").toAbsolutePath();
        while (!Files.exists(boot.resolve("sql/yixiang-drink-open.sql"))) boot = boot.getParent();
        if (!mysql) {
            jdbc.execute("CREATE TABLE yshop_store_shop(id BIGINT PRIMARY KEY,name VARCHAR(100) NOT NULL,mobile VARCHAR(20) NOT NULL,images VARCHAR(100) NOT NULL,address VARCHAR(100) NOT NULL,address_map VARCHAR(100) NOT NULL,start_time TIMESTAMP NOT NULL,end_time TIMESTAMP NOT NULL,lng VARCHAR(30) NOT NULL,lat VARCHAR(30) NOT NULL,status INT NOT NULL,admin_id VARCHAR(100) NOT NULL,deleted BIT DEFAULT 0)");
            jdbc.execute("CREATE TABLE yshop_store_order(id BIGINT PRIMARY KEY,order_id VARCHAR(32) NOT NULL UNIQUE,uid BIGINT NOT NULL,shop_id BIGINT,tenant_id BIGINT DEFAULT 0,order_type VARCHAR(32),paid INT NOT NULL,status INT NOT NULL,refund_status INT NOT NULL,refund_price DECIMAL(8,2) NOT NULL DEFAULT 0,pay_price DECIMAL(8,2) NOT NULL DEFAULT 0,pay_time TIMESTAMP,total_num INT NOT NULL DEFAULT 0,is_system_del INT DEFAULT 0,deleted BIT DEFAULT 0,fulfillment_status VARCHAR(24) DEFAULT 'LEGACY',pay_type VARCHAR(32) NOT NULL,mark VARCHAR(512) NOT NULL,cost DECIMAL(8,2) NOT NULL,create_time TIMESTAMP NOT NULL)");
            jdbc.execute("CREATE TABLE yshop_store_order_cart_info(id BIGINT AUTO_INCREMENT PRIMARY KEY,oid BIGINT NOT NULL,order_id VARCHAR(32),cart_info CLOB NOT NULL)");
        } else ensureMysqlFixtures();
        insertShop(shopOne, "101");
        insertShop(shopTwo, "102");
        permissions = mock(PermissionApi.class);
        when(permissions.hasAnyRoles(anyLong(), any(String[].class)))
                .thenAnswer(call -> headquartersUsers.contains(call.getArgument(0)));
        service = new StoreOrderStatisticsService(jdbc, new StoreAccessService(jdbc, permissions), false);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        if (jdbc != null) {
            jdbc.update("DELETE FROM yshop_store_order WHERE id>=? AND id<?", orderBase, orderBase + 100);
            jdbc.update("DELETE FROM yshop_store_order_cart_info WHERE oid>=? AND oid<?", orderBase, orderBase + 100);
            jdbc.update("DELETE FROM yshop_store_shop WHERE id IN (?,?)", shopOne, shopTwo);
        }
    }

    private void insertShop(long id, String adminId) {
        jdbc.update("INSERT INTO yshop_store_shop(id,name,mobile,images,address,address_map,start_time,end_time,lng,lat,status,admin_id,deleted) VALUES(?,?,'','[]','','','2026-10-01 00:00:00','2026-10-01 23:59:59','0','0',1,?,0)", id, "Synthetic " + id, adminId);
    }

    private boolean mysqlTableMissing(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", Integer.class, table) == 0;
    }

    private void ensureMysqlFixtures() throws Exception {
        String seed = Files.readString(boot.resolve("sql/yixiang-drink-open.sql"));
        String ddl = seed;
        for (String table : java.util.List.of("yshop_store_shop", "yshop_store_order", "yshop_store_order_cart_info")) {
            if (mysqlTableMissing(table)) {
                var matcher = java.util.regex.Pattern.compile("CREATE TABLE `" + table + "`.*?;", java.util.regex.Pattern.DOTALL).matcher(ddl);
                assertTrue(matcher.find(), "Missing canonical MySQL DDL for " + table);
                jdbc.execute(matcher.group());
            }
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order' AND COLUMN_NAME='fulfillment_status'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE yshop_store_order ADD COLUMN fulfillment_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY'");
    }

    private long insertOrder(long shopId, int tenant, int paid, int refundStatus, String refundPrice,
                             String payPrice, int quantity, String fulfillment, String payTime) {
        long id = orderBase + ++nextOrder;
        jdbc.update("INSERT INTO yshop_store_order(id,order_id,uid,shop_id,tenant_id,order_type,paid,status,refund_status,refund_price,pay_price,pay_time,total_num,is_system_del,deleted,fulfillment_status,pay_type,mark,cost,create_time) VALUES(?,?,?,?,?,'takein',?,0,?,?,?,?,?,0,0,?,'synthetic','Synthetic order',0,CURRENT_TIMESTAMP)",
                id, "stats-" + id, 1L, shopId, tenant, paid, refundStatus, new BigDecimal(refundPrice), new BigDecimal(payPrice),
                payTime == null ? null : LocalDateTime.parse(payTime), quantity, fulfillment);
        return id;
    }

    private void login(long uid, Long tenantId) {
        LoginUser user = new LoginUser();
        user.setId(uid);
        user.setUserType(2);
        user.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
        TenantContextHolder.setTenantId(tenantId);
    }

    private static void amount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void storeSummaryCannotReadForeignShop() {
        insertOrder(shopTwo, 0, 1, 0, "0", "20", 2, "WAIT_ACCEPT", "2031-10-22T10:00:00");
        login(101, 0L);
        assertEquals(java.util.List.of(shopOne), service.authorizedShops().stream().map(StoreOrderStatisticsService.ShopOption::id).toList());
        assertThrows(AccessDeniedException.class,
                () -> service.summary(shopTwo, LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-23")));
    }

    @Test
    void headquartersShopOptionsOnlyIncludeShopsWithOrdersInCurrentTenant() {
        insertOrder(shopOne, 101, 1, 0, "0", "20", 2, "WAIT_ACCEPT", "2031-10-22T10:00:00");
        insertOrder(shopTwo, 102, 1, 0, "0", "30", 3, "WAIT_ACCEPT", "2031-10-22T11:00:00");
        headquartersUsers.add(103L);
        login(103, 101L);

        assertEquals(java.util.List.of(shopOne), service.authorizedShops().stream()
                .map(StoreOrderStatisticsService.ShopOption::id).toList());
    }

    @Test
    void headquartersEqualsAuthorizedStoreTotals() {
        long multiItemOrder = insertOrder(shopOne, 0, 1, 0, "0", "12.50", 2, "COLLECTED", "2031-10-22T12:00:00");
        jdbc.update("INSERT INTO yshop_store_order_cart_info(oid,order_id,cart_info) VALUES(?,?,?),(?,?,?)", multiItemOrder,
                "stats-" + multiItemOrder, "synthetic-item-1", multiItemOrder, "stats-" + multiItemOrder, "synthetic-item-2");
        insertOrder(shopOne, 0, 1, 1, "3.50", "8.00", 1, "COLLECTED", "2031-10-22T16:00:00");
        insertOrder(shopTwo, 0, 1, 2, "6.00", "20.00", 3, "COLLECTED", "2031-10-22T18:30:00");
        insertOrder(shopTwo, 1, 1, 0, "0", "100.00", 10, "COLLECTED", "2031-10-22T19:00:00");
        insertOrder(shopOne, 0, 1, 0, "0", "5.00", 1, "WAIT_ACCEPT", "2031-10-20T08:00:00");
        insertOrder(shopOne, 0, 1, 0, "0", "4.00", 1, "ACCEPTED", "2031-10-20T08:00:00");
        insertOrder(shopOne, 0, 1, 0, "0", "4.00", 1, "PREPARING", "2031-10-20T08:00:00");
        insertOrder(shopOne, 0, 1, 0, "0", "4.00", 1, "READY", "2031-10-20T08:00:00");
        long canceledUnpaid = insertOrder(shopOne, 0, 0, 0, "0", "99.00", 99, "LEGACY", "2031-10-22T20:00:00");
        jdbc.update("UPDATE yshop_store_order SET status=-1 WHERE id=?", canceledUnpaid);

        login(101, 0L);
        long summaryStarted = System.nanoTime();
        var branch = service.summary(null, LocalDate.parse("2031-10-22"), LocalDate.parse("2031-10-23"));
        System.out.printf(java.util.Locale.ROOT, "STORE_ORDER_STATS_SUMMARY_MS=%.3f mysql=%s%n", (System.nanoTime() - summaryStarted) / 1_000_000.0, mysql);
        if (mysql) {
            Map<String, Object> plan = jdbc.queryForMap(
                    "EXPLAIN SELECT id FROM yshop_store_order WHERE paid=1 AND pay_time>=? AND pay_time<? "
                            + "AND COALESCE(tenant_id,0)=? AND shop_id=? AND is_system_del=0 AND deleted=0",
                    LocalDateTime.parse("2031-10-22T00:00:00"), LocalDateTime.parse("2031-10-23T00:00:00"), 0L, shopOne);
            System.out.println("STORE_ORDER_STATS_QUERY_PLAN=" + plan);
        }
        assertEquals(java.util.List.of(shopOne), branch.authorizedShopIds());
        assertEquals(2L, branch.paidOrderCount());
        amount("20.50", branch.grossPaidAmount());
        assertEquals(3L, branch.soldQuantity());
        assertEquals(1L, branch.refundOrderCount());
        assertEquals(1L, branch.refundPendingCount());
        assertEquals(0L, branch.refundCompletedCount());
        amount("3.50", branch.refundAmount());
        assertEquals(1L, branch.waitAcceptQueueCount());
        assertEquals(1L, branch.acceptedQueueCount());
        assertEquals(1L, branch.preparingQueueCount());
        assertEquals(1L, branch.readyQueueCount());
        var branchDetails = service.details(null, LocalDate.parse("2031-10-22"), LocalDate.parse("2031-10-23"), 1, 20);
        assertEquals(branch.paidOrderCount(), branchDetails.getTotal());

        login(103, 0L);
        headquartersUsers.add(103L);
        var all = service.summary(null, LocalDate.parse("2031-10-22"), LocalDate.parse("2031-10-23"));
        assertTrue(all.headquarters());
        assertNull(all.authorizedShopIds());
        assertEquals(3L, all.paidOrderCount());
        amount("40.50", all.grossPaidAmount());
        assertEquals(6L, all.soldQuantity());
        assertEquals(2L, all.refundOrderCount());
        assertEquals(1L, all.refundPendingCount());
        assertEquals(1L, all.refundCompletedCount());
        amount("9.50", all.refundAmount());
        assertEquals(1L, all.waitAcceptQueueCount());
        assertEquals(3L, service.details(null, LocalDate.parse("2031-10-22"), LocalDate.parse("2031-10-23"), 1, 20).getTotal());
    }

    @Test
    void shanghaiMidnightBelongsToOneDay() {
        insertOrder(shopOne, 0, 1, 0, "0", "4.00", 1, "COLLECTED", "2031-10-22T00:00:00");
        insertOrder(shopOne, 0, 1, 0, "0", "9.00", 1, "COLLECTED", "2031-10-23T00:00:00");
        login(101, 0L);
        var oneDay = service.summary(shopOne, LocalDate.parse("2031-10-22"), LocalDate.parse("2031-10-23"));
        assertEquals(1L, oneDay.paidOrderCount());
        amount("4.00", oneDay.grossPaidAmount());
    }

    @Test
    void emptyWindowReturnsZeroMetrics() {
        login(101, 0L);
        var empty = service.summary(shopOne, LocalDate.parse("2031-10-22"), LocalDate.parse("2031-10-23"));
        assertEquals(0L, empty.paidOrderCount());
        amount("0.00", empty.grossPaidAmount());
        assertEquals(0L, empty.soldQuantity());
        assertEquals(0L, empty.refundOrderCount());
        amount("0.00", empty.refundAmount());
        assertEquals(0L, empty.waitAcceptQueueCount());
        assertEquals(0L, empty.acceptedQueueCount());
        assertEquals(0L, empty.preparingQueueCount());
        assertEquals(0L, empty.readyQueueCount());
    }
}
