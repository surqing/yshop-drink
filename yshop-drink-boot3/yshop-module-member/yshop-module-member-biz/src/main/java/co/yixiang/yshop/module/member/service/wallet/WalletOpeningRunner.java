package co.yixiang.yshop.module.member.service.wallet;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit operator opt-in; default OFF. Resumable and balance-preserving. */
@Component
@Slf4j
@ConditionalOnProperty(name = "yshop.wallet.opening-migration-enabled", havingValue = "true")
public class WalletOpeningRunner implements ApplicationRunner {
    private final WalletOpeningMigration migration;

    public WalletOpeningRunner(WalletOpeningMigration migration) {
        this.migration = migration;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("wallet opening migration complete createdCount={}", migration.run());
    }
}
