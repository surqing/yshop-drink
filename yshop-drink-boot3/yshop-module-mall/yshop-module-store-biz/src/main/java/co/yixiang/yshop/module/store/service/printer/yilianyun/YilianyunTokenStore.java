package co.yixiang.yshop.module.store.service.printer.yilianyun;

import co.yixiang.yshop.module.store.dal.mysql.printer.PrinterRepository;
import co.yixiang.yshop.module.store.service.printer.PrinterSecretCipher;
import co.yixiang.yshop.module.store.service.printer.PrinterProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

@Component
public class YilianyunTokenStore {
    private final PrinterRepository repo;
    private final PrinterSecretCipher cipher;
    private final TransactionTemplate tx;
    public YilianyunTokenStore(PrinterRepository repo,PrinterSecretCipher cipher,PlatformTransactionManager manager) {
        this.repo=repo; this.cipher=cipher; tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public String get(String accountKey,Supplier<String> fetch) {
        PrinterProvider.assertOutsideTransaction();
        return tx.execute(status->{
            repo.ensureToken(accountKey);
            String encrypted=repo.lockToken(accountKey);
            if(encrypted!=null) return cipher.decrypt(encrypted,"provider-token:"+accountKey);
            // Validate key before consuming the provider's limited daily token quota.
            cipher.encrypt("key-check","provider-token:"+accountKey);
            String token=fetch.get();
            repo.token(accountKey,cipher.encrypt(token,"provider-token:"+accountKey));
            return token;
        });
    }
}
