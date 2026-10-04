package co.yixiang.yshop.module.member.service.wallet;

import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;
import co.yixiang.yshop.module.member.dal.mysql.wallet.RechargeMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class RechargeService {
    private final RechargeMapper mapper;
    private final WalletService wallets;
    private final boolean enabled;

    public RechargeService(
            RechargeMapper mapper,
            WalletService wallets,
            @Value("${yshop.wallet.synthetic-recharge-enabled:false}") boolean enabled) {
        this.mapper = mapper;
        this.wallets = wallets;
        this.enabled = enabled;
    }

    private void allowed() {
        if (!enabled) throw new IllegalStateException("SYNTHETIC_RECHARGE_DISABLED");
    }

    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public RechargeOrder createSynthetic(Long uid, String id, BigDecimal pay, BigDecimal bonus) {
        allowed();
        WalletService.identifier(id);
        pay = WalletService.money(pay, false);
        bonus = WalletService.money(bonus, true);
        if (uid == null || uid <= 0) throw new IllegalArgumentException("INVALID_RECHARGE_MEMBER");
        RechargeOrder row = new RechargeOrder();
        row.setRechargeNo(id);
        row.setUid(uid);
        row.setPayAmount(pay);
        row.setBonusAmount(bonus);
        row.setCreditAmount(WalletService.money(pay.add(bonus), false));
        row.setProvider("SYNTHETIC");
        row.setStatus("CREATED");
        mapper.insert(row);
        return row;
    }

    /** Test-only verified completion boundary: no HTTP endpoint, provider callback or prepay. */
    @TenantIgnore
    @Transactional(rollbackFor = Exception.class)
    public boolean completeSynthetic(String id, String transaction, BigDecimal verifiedPaid) {
        allowed();
        WalletService.identifier(id);
        WalletService.identifier(transaction);
        RechargeOrder row = mapper.lock(id);
        if (row == null || !"SYNTHETIC".equals(row.getProvider()))
            throw new IllegalArgumentException("INVALID_SYNTHETIC_RECHARGE");
        if (row.getPayAmount().compareTo(WalletService.money(verifiedPaid, false)) != 0)
            throw new IllegalStateException("RECHARGE_AMOUNT_MISMATCH");
        if ("SUCCESS".equals(row.getStatus())) {
            if (!transaction.equals(row.getProviderTransactionId()))
                throw new IllegalStateException("RECHARGE_TRANSACTION_CONFLICT");
            return false;
        }
        if (!"CREATED".equals(row.getStatus()))
            throw new IllegalStateException("INVALID_RECHARGE_STATE");
        wallets.credit(
                row.getUid(), row.getCreditAmount(), WalletType.RECHARGE, id, "recharge:" + id);
        if (mapper.complete(id, transaction) != 1)
            throw new IllegalStateException("RECHARGE_FINALIZATION_FAILED");
        return true;
    }
}
