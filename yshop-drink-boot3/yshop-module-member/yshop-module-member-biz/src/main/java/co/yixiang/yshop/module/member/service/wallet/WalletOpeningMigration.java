package co.yixiang.yshop.module.member.service.wallet;

import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;
import co.yixiang.yshop.module.member.dal.mysql.wallet.WalletMapper;

import org.springframework.stereotype.Service;

@Service
public class WalletOpeningMigration {
    private final WalletMapper mapper;
    private final WalletService wallets;

    public WalletOpeningMigration(WalletMapper mapper, WalletService wallets) {
        this.mapper = mapper;
        this.wallets = wallets;
    }

    /** Operator-invoked, resumable. No startup runner or balance UPDATE. */
    @TenantIgnore
    public int run() {
        int negative = mapper.negativeCount();
        if (negative > 0) throw new IllegalStateException("NEGATIVE_WALLET_COUNT=" + negative);
        int created = 0;
        for (Long uid : mapper.userIds()) if (wallets.open(uid)) created++;
        return created;
    }
}
