package co.yixiang.yshop.module.pay.credential;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disabled unless explicitly requested after schema expansion and private key configuration. */
@Component
@ConditionalOnProperty(name = "yshop.pay.credentials.migrate-once", havingValue = "true")
public class PaymentCredentialMigrationRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentCredentialMigrationRunner.class);
    private final PaymentCredentialMigrationService migration;
    public PaymentCredentialMigrationRunner(PaymentCredentialMigrationService migration) { this.migration = migration; }
    @Override public void run(ApplicationArguments args) {
        var result = migration.migrate();
        LOG.info("Payment credential migration verified: records={}, migratedFields={}, verifiedEncryptedFields={}",
                result.records(), result.migratedFields(), result.verifiedEncryptedFields());
    }
}
