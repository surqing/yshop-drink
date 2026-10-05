package co.yixiang.yshop.module.pay.preflight;

import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Aggregate-only operator diagnostics. No provider calls, row locks or financial writes. */
@Service
public class LivePaymentAuditService {
    private final JdbcTemplate jdbc;

    public LivePaymentAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(boolean available, Map<String, Long> counts, Instant observedAt) {
        public boolean hasBlockingRisk() {
            return !available
                    || counts.entrySet().stream()
                            .anyMatch(
                                    e ->
                                            !Set.of(
                                                                    "ACTIVE_ATTEMPTS",
                                                                    "PRE_ATTEMPT_EXTERNAL_RECEIPTS",
                                                                    "LEGACY_EXTERNAL_UNPAID_CANDIDATES")
                                                            .contains(e.getKey())
                                                    && e.getValue() > 0);
        }
    }

    private static final Map<String, List<String>> COLUMNS =
            Map.of(
                    "merchant_details",
                            List.of(
                                    "details_id",
                                    "appid",
                                    "mch_id",
                                    "pay_type",
                                    "deleted",
                                    "is_test",
                                    "notify_url",
                                    "key_private",
                                    "key_public",
                                    "api_v3_key",
                                    "wechat_api_version",
                                    "merchant_certificate_serial",
                                    "platform_public_key_id"),
                    "yshop_order_payment",
                            List.of(
                                    "id",
                                    "order_id",
                                    "provider",
                                    "merchant_details_id",
                                    "out_trade_no",
                                    "provider_transaction_id",
                                    "amount_cents",
                                    "status",
                                    "failure_reason",
                                    "attempt_id",
                                    "success_order_id"),
                    "yshop_order_payment_conflict",
                            List.of("id", "event_id", "claimed_order_id", "reason", "received_at"),
                    "yshop_order_payment_attempt",
                            List.of(
                                    "attempt_id",
                                    "order_id",
                                    "uid",
                                    "idempotency_key",
                                    "provider",
                                    "merchant_details_id",
                                    "amount_cents",
                                    "currency",
                                    "appid",
                                    "merchant_identity",
                                    "provider_order_reference",
                                    "prepay_reference",
                                    "provider_transaction_id",
                                    "status",
                                    "payment_event_id",
                                    "paid_at",
                                    "create_time",
                                    "update_time",
                                    "active_order_id",
                                    "prepay_requested_at",
                                    "reconciliation_token",
                                    "reconciliation_lease_until",
                                    "remote_terminal_state",
                                    "remote_confirmed_at"),
                    "yshop_member_wallet_transaction",
                            List.of(
                                    "id",
                                    "uid",
                                    "idempotency_key",
                                    "type",
                                    "business_id",
                                    "direction",
                                    "amount",
                                    "balance_before",
                                    "balance_after"),
                    "yshop_member_recharge_order",
                            List.of(
                                    "recharge_no",
                                    "uid",
                                    "pay_amount",
                                    "bonus_amount",
                                    "credit_amount",
                                    "status",
                                    "provider_transaction_id"),
                    "yshop_store_order",
                            List.of(
                                    "order_id",
                                    "uid",
                                    "paid",
                                    "pay_type",
                                    "status",
                                    "refund_status",
                                    "deleted",
                                    "is_system_del",
                                    "pay_price"),
                    "yshop_user", List.of("id", "now_money"));

    /** Existing trigger migrations require MySQL >= 8.0.29; only the 8.x family is supported. */
    static boolean supportedMysqlVersion(String version) {
        if (version == null || version.toLowerCase(Locale.ROOT).contains("mariadb")) return false;
        var match =
                java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
                        .matcher(version);
        if (!match.matches()) return false;
        try {
            int major = Integer.parseInt(match.group(1));
            int minor = Integer.parseInt(match.group(2));
            int patch = Integer.parseInt(match.group(3));
            return major == 8 && (minor > 0 || patch >= 29);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public String mysqlVersion() {
        try {
            return jdbc.queryForObject("SELECT VERSION()", String.class);
        } catch (RuntimeException ignored) {
            return "UNAVAILABLE";
        }
    }

    public boolean schemaComplete() {
        try {
            String version = jdbc.queryForObject("SELECT VERSION()", String.class);
            if (!supportedMysqlVersion(version)) return false;
            for (var table : COLUMNS.entrySet()) {
                if (!"InnoDB"
                        .equals(
                                jdbc.queryForObject(
                                        "SELECT ENGINE FROM information_schema.TABLES WHERE"
                                                + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
                                        String.class,
                                        table.getKey()))) return false;
                var actual =
                        jdbc.queryForList(
                                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE"
                                        + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
                                String.class,
                                table.getKey());
                if (!actual.containsAll(table.getValue())) return false;
            }
            String generated =
                    jdbc.queryForObject(
                            "SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE"
                                    + " TABLE_SCHEMA=DATABASE() AND"
                                    + " TABLE_NAME='yshop_order_payment_attempt' AND"
                                    + " COLUMN_NAME='active_order_id'",
                            String.class);
            if (generated == null
                    || !generated.contains("CREATED")
                    || !generated.contains("PREPAY_CREATED")
                    || !generated.contains("order_id")) return false;
            for (var index :
                    Map.ofEntries(
                                    Map.entry("uk_attempt_key", "order_id,idempotency_key"),
                                            Map.entry("uk_attempt_active", "active_order_id"),
                                    Map.entry("uk_attempt_order_ref", "provider_order_reference"),
                                            Map.entry(
                                                    "uk_attempt_prepay",
                                                    "provider,prepay_reference"),
                                    Map.entry(
                                                    "uk_attempt_transaction",
                                                    "provider,provider_transaction_id"),
                                            Map.entry("uk_attempt_event", "payment_event_id"))
                            .entrySet())
                if (!unique("yshop_order_payment_attempt", index.getKey(), index.getValue()))
                    return false;
            if (!unique(
                            "yshop_order_payment",
                            "uk_payment_transaction",
                            "provider,provider_transaction_id")
                    || !unique(
                            "yshop_order_payment", "uk_payment_success_order", "success_order_id")
                    || !unique(
                            "yshop_member_wallet_transaction",
                            "uk_wallet_idempotency",
                            "idempotency_key")
                    || !unique(
                            "yshop_member_wallet_transaction",
                            "uk_wallet_business",
                            "type,business_id")) return false;
            for (String name :
                    List.of(
                            "chk_attempt_identity",
                            "chk_attempt_state",
                            "chk_attempt_paid",
                            "chk_attempt_prepay",
                            "chk_attempt_remote_terminal",
                            "chk_payment_amount",
                            "chk_payment_success",
                            "chk_wallet_nonnegative",
                            "chk_wallet_equation",
                            "chk_recharge_status"))
                if (jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE"
                                        + " CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME=? AND"
                                        + " CONSTRAINT_TYPE='CHECK' AND ENFORCED='YES'",
                                Long.class,
                                name)
                        != 1) return false;
            for (var trigger :
                    Map.of(
                                    "payment_attempt_immutable",
                                    "IMMUTABLE_PAYMENT_ATTEMPT",
                                    "payment_attempt_no_delete",
                                    "IMMUTABLE_PAYMENT_ATTEMPT",
                                    "payment_attempt_v3_request_immutable",
                                    "IMMUTABLE_PREPAY_REQUEST",
                                    "wallet_no_update",
                                    "IMMUTABLE_WALLET_LEDGER",
                                    "wallet_no_delete",
                                    "IMMUTABLE_WALLET_LEDGER")
                            .entrySet()) {
                String sql =
                        jdbc.queryForObject(
                                "SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE"
                                        + " TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?",
                                String.class,
                                trigger.getKey());
                if (sql == null || !sql.contains(trigger.getValue())) return false;
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean unique(String table, String index, String columns) {
        String actual =
                jdbc.queryForObject(
                        "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM"
                                + " information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND"
                                + " TABLE_NAME=? AND INDEX_NAME=? AND NON_UNIQUE=0",
                        String.class,
                        table,
                        index);
        return columns.equals(actual);
    }

    public Long databaseOffsetMillis(Instant now) {
        try {
            return jdbc.queryForObject(
                                    "SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6))*1000",
                                    java.math.BigDecimal.class)
                            .longValue()
                    - now.toEpochMilli();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @TenantIgnore
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Snapshot snapshot() {
        var counts = new TreeMap<String, Long>();
        try {
            count(
                    counts,
                    "ACTIVE_ATTEMPTS",
                    "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS NOT"
                            + " NULL");
            count(
                    counts,
                    "UNCERTAIN_ATTEMPTS",
                    "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS NOT"
                        + " NULL AND prepay_requested_at IS NOT NULL AND prepay_reference IS NULL");
            count(
                    counts,
                    "EXPIRED_LEASES",
                    "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS NOT"
                            + " NULL AND reconciliation_token IS NOT NULL AND"
                            + " reconciliation_lease_until<CURRENT_TIMESTAMP");
            count(
                    counts,
                    "STALE_ACTIVE_ATTEMPTS",
                    "SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE active_order_id IS NOT"
                            + " NULL AND create_time < ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1800)));
            count(
                    counts,
                    "ACTIVE_ORDER_CONFLICTS",
                    "SELECT COUNT(*) FROM yshop_order_payment_attempt a LEFT JOIN yshop_store_order"
                            + " o ON a.order_id=o.order_id WHERE a.active_order_id IS NOT NULL AND"
                            + " (o.id IS NULL OR o.paid=1 OR o.deleted<>0 OR o.is_system_del<>0 OR"
                            + " o.refund_status<>0 OR o.status<>0 OR o.uid<>a.uid OR"
                            + " o.pay_price*100<>a.amount_cents)");
            count(
                    counts,
                    "PAYMENT_CONFLICT",
                    "SELECT COUNT(*) FROM yshop_order_payment WHERE status='PAYMENT_CONFLICT'");
            count(
                    counts,
                    "RECONCILIATION_REQUIRED",
                    "SELECT COUNT(*) FROM yshop_order_payment WHERE"
                            + " status='RECONCILIATION_REQUIRED'");
            count(
                    counts,
                    "TRANSACTION_CONFLICT_RECEIPTS",
                    "SELECT COUNT(*) FROM yshop_order_payment_conflict");
            count(
                    counts,
                    "TERMINAL_LATE_SUCCESS",
                    "SELECT COUNT(*) FROM yshop_order_payment p JOIN yshop_order_payment_attempt a"
                            + " ON p.attempt_id=a.attempt_id WHERE a.status IN"
                            + " ('FAILED','EXPIRED','CANCELED') AND p.status IN"
                            + " ('RECONCILIATION_REQUIRED','PAYMENT_CONFLICT')");
            count(
                    counts,
                    "PRE_ATTEMPT_EXTERNAL_RECEIPTS",
                    "SELECT COUNT(*) FROM yshop_order_payment WHERE attempt_id IS NULL AND provider"
                            + " IN ('WECHAT','ALIPAY')");
            // No persisted legacy prepay URL registry exists: this is a conservative candidate
            // count,
            // never proof that an unpaid historical order has or does not have a payable remote
            // URL.
            count(
                    counts,
                    "LEGACY_EXTERNAL_UNPAID_CANDIDATES",
                    "SELECT COUNT(*) FROM yshop_store_order o WHERE o.paid=0 AND o.pay_type IN"
                            + " ('weixin','alipay') AND NOT EXISTS (SELECT 1 FROM"
                            + " yshop_order_payment_attempt a WHERE a.order_id=o.order_id)");
            return new Snapshot(true, Collections.unmodifiableMap(counts), Instant.now());
        } catch (RuntimeException ignored) {
            return new Snapshot(false, Map.of(), Instant.now());
        }
    }

    private void count(Map<String, Long> counts, String name, String sql, Object... args) {
        counts.put(name, jdbc.queryForObject(sql, Long.class, args));
    }
}
