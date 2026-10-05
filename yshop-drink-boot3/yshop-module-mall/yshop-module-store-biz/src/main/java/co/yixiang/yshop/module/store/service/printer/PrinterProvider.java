package co.yixiang.yshop.module.store.service.printer;

import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;

/** Brand and transport boundary; no vendor commands escape this interface. */
public interface PrinterProvider {
    ProviderInfo info();
    void validate(String deviceCode, String connectionType, Map<String, String> configuration);
    default void validateAuthorization(Map<String, String> configuration) {}
    /** Prepare credentials outside device transactions; the session must not fetch tokens. */
    PrinterSession openSession();
    static void assertOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new PrinterFailure("SESSION_IN_TRANSACTION", false);
    }
}
