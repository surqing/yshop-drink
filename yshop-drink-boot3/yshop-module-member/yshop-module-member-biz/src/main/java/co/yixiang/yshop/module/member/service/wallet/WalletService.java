package co.yixiang.yshop.module.member.service.wallet;

import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;
import co.yixiang.yshop.module.member.dal.mysql.wallet.WalletMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

@Service
public class WalletService {
    private final WalletMapper mapper;

    public WalletService(WalletMapper mapper) {
        this.mapper = mapper;
    }

    public record Result(boolean first, WalletTransaction transaction) {}

    public static BigDecimal money(BigDecimal amount, boolean zeroAllowed) {
        if (amount == null) throw new IllegalArgumentException("INVALID_WALLET_AMOUNT");
        BigDecimal exact = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (exact.signum() < 0
                || (!zeroAllowed && exact.signum() == 0)
                || exact.compareTo(new BigDecimal("999999.99")) > 0)
            throw new IllegalArgumentException("INVALID_WALLET_AMOUNT");
        return exact;
    }

    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9:_-]{1,128}"))
            throw new IllegalArgumentException("INVALID_WALLET_REFERENCE");
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public Result debit(Long uid, BigDecimal amount, WalletType type, String business, String key) {
        if (type != WalletType.ORDER_PAYMENT && type != WalletType.ADMIN_ADJUSTMENT)
            throw new IllegalArgumentException("INVALID_DEBIT_TYPE");
        return change(uid, money(amount, false), type, business, key, "DEBIT");
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public Result credit(
            Long uid, BigDecimal amount, WalletType type, String business, String key) {
        if (type != WalletType.RECHARGE
                && type != WalletType.ADMIN_ADJUSTMENT
                && type != WalletType.ORDER_REFUND)
            throw new IllegalArgumentException("INVALID_CREDIT_TYPE");
        return change(uid, money(amount, false), type, business, key, "CREDIT");
    }

    private Result change(
            Long uid,
            BigDecimal amount,
            WalletType type,
            String business,
            String key,
            String direction) {
        identifier(business);
        identifier(key);
        if (key.startsWith("opening:")) throw new IllegalArgumentException("RESERVED_WALLET_KEY");
        WalletBalance user = mapper.lockUser(uid);
        if (user == null || Boolean.TRUE.equals(user.getDeleted()))
            throw new IllegalArgumentException("WALLET_MEMBER_UNAVAILABLE");
        BigDecimal before = money(user.getNowMoney(), true);
        ensureOpening(user);
        var current = mapper.currentTransactions(uid);
        var total =
                current.stream()
                        .map(
                                row ->
                                        row.getDirection().equals("CREDIT")
                                                ? row.getAmount()
                                                : row.getAmount().negate())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(before) != 0)
            throw new IllegalStateException("WALLET_RECONCILIATION_REQUIRED");
        WalletTransaction existing =
                current.stream()
                        .filter(row -> row.getIdempotencyKey().equals(key))
                        .findFirst()
                        .orElseGet(() -> mapper.find(key));
        if (existing != null) {
            if (!Objects.equals(existing.getUid(), uid)
                    || !existing.getDirection().equals(direction)
                    || !existing.getType().equals(type.name())
                    || !existing.getBusinessId().equals(business)
                    || existing.getAmount().compareTo(amount) != 0)
                throw new IllegalStateException("WALLET_IDEMPOTENCY_CONFLICT");
            return new Result(false, existing);
        }
        BigDecimal after = direction.equals("DEBIT") ? before.subtract(amount) : before.add(amount);
        if (after.signum() < 0) throw new IllegalStateException("INSUFFICIENT_WALLET_BALANCE");
        money(after, true);
        if (mapper.change(uid, before, after) != 1)
            throw new IllegalStateException("WALLET_BALANCE_TRANSITION_FAILED");
        WalletTransaction row = row(uid, direction, type, amount, before, after, business, key);
        try {
            if (mapper.insert(row) != 1)
                throw new IllegalStateException("WALLET_LEDGER_INSERT_FAILED");
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            throw new IllegalStateException("WALLET_IDEMPOTENCY_CONFLICT");
        }
        return new Result(true, row);
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public boolean open(Long uid) {
        WalletBalance user = mapper.lockUser(uid);
        if (user == null) throw new IllegalArgumentException("WALLET_MEMBER_UNAVAILABLE");
        money(user.getNowMoney(), true);
        return ensureOpening(user);
    }

    private boolean ensureOpening(WalletBalance user) {
        String key = "opening:" + user.getId();
        var current = mapper.currentTransactions(user.getId());
        WalletTransaction existing =
                current.stream()
                        .filter(row -> row.getIdempotencyKey().equals(key))
                        .findFirst()
                        .orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getUid(), user.getId())
                    || !existing.getType().equals("OPENING_BALANCE")
                    || !existing.getDirection().equals("CREDIT")
                    || existing.getBalanceBefore().signum() != 0)
                throw new IllegalStateException("WALLET_OPENING_CONFLICT");
            return false;
        }
        // Opening records current money only; this method NEVER updates the user balance.
        if (!current.isEmpty()) throw new IllegalStateException("WALLET_OPENING_REQUIRED");
        mapper.insert(
                row(
                        user.getId(),
                        "CREDIT",
                        WalletType.OPENING_BALANCE,
                        money(user.getNowMoney(), true),
                        BigDecimal.ZERO,
                        user.getNowMoney(),
                        "member:" + user.getId(),
                        key));
        return true;
    }

    private WalletTransaction row(
            Long uid,
            String direction,
            WalletType type,
            BigDecimal amount,
            BigDecimal before,
            BigDecimal after,
            String business,
            String key) {
        WalletTransaction row = new WalletTransaction();
        row.setId(UUID.randomUUID().toString().replace("-", ""));
        row.setUid(uid);
        row.setDirection(direction);
        row.setType(type.name());
        row.setAmount(amount);
        row.setBalanceBefore(before);
        row.setBalanceAfter(after);
        row.setBusinessId(business);
        row.setIdempotencyKey(key);
        return row;
    }

    /** Caller must join the debit transaction; used by internal payment finalization. */
    @TenantIgnore
    public void requireOrderDebit(Long uid, String order, BigDecimal amount) {
        WalletTransaction row = mapper.find("order:" + order);
        if (row == null
                || !Objects.equals(row.getUid(), uid)
                || !row.getType().equals("ORDER_PAYMENT")
                || !row.getDirection().equals("DEBIT")
                || !row.getBusinessId().equals(order)
                || row.getAmount().compareTo(money(amount, false)) != 0)
            throw new IllegalStateException("BALANCE_PAYMENT_REQUIRES_WALLET_DEBIT");
    }
}
