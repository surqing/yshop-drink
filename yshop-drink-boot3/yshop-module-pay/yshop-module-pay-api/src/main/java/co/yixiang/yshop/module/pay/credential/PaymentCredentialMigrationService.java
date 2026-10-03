package co.yixiang.yshop.module.pay.credential;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.util.Objects;

/** Explicit, all-or-nothing migration, including logically deleted records. Never logs values. */
@Service
public class PaymentCredentialMigrationService {
    private static final String[] FIELDS = {"keyPrivate", "keyCertPwd", "keyCert"};
    private static final String COLUMNS = "key_private, key_cert_pwd, key_cert";
    private final JdbcTemplate jdbc;
    private final PaymentCredentialCryptoService crypto;

    public PaymentCredentialMigrationService(JdbcTemplate jdbc, PaymentCredentialCryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Transactional(rollbackFor = Exception.class)
    public MigrationResult migrate() {
        crypto.requireKey();
        return jdbc.execute((ConnectionCallback<MigrationResult>) connection -> {
            int records = 0, migratedFields = 0, verifiedEncryptedFields = 0;
            try (PreparedStatement read = connection.prepareStatement("SELECT details_id, " + COLUMNS
                    + " FROM merchant_details ORDER BY details_id FOR UPDATE");
                 ResultSet rows = read.executeQuery();
                 PreparedStatement update = connection.prepareStatement("UPDATE merchant_details SET key_private=?, "
                         + "key_cert_pwd=?, key_cert=? WHERE details_id=?");
                 PreparedStatement verify = connection.prepareStatement("SELECT " + COLUMNS
                         + " FROM merchant_details WHERE details_id=?")) {
                while (rows.next()) {
                    records++;
                    String id = rows.getString(1);
                    String[] stored = new String[3];
                    String[] expected = new String[3];
                    boolean changed = false;
                    for (int i = 0; i < FIELDS.length; i++) {
                        String old = rows.getString(i + 2);
                        stored[i] = old;
                        if (PaymentCredentialCryptoService.blank(old)) continue;
                        if (old.trim().startsWith("enc:")) {
                            // Unknown version/malformed/wrong key stops the whole transaction.
                            expected[i] = crypto.decrypt(id, FIELDS[i], old);
                            verifiedEncryptedFields++;
                        } else {
                            expected[i] = old;
                            stored[i] = crypto.encrypt(id, FIELDS[i], old);
                            migratedFields++;
                            changed = true;
                        }
                    }
                    if (!changed) continue;
                    for (int i = 0; i < 3; i++) update.setString(i + 1, stored[i]);
                    update.setString(4, id);
                    if (update.executeUpdate() != 1) throw PaymentCredentialCryptoService.failure("Payment credential migration write failed");
                    verify.setString(1, id);
                    try (ResultSet written = verify.executeQuery()) {
                        if (!written.next()) throw PaymentCredentialCryptoService.failure("Payment credential migration verification failed");
                        for (int i = 0; i < 3; i++) {
                            String actual = written.getString(i + 1);
                            if (!Objects.equals(stored[i], actual) || (expected[i] != null
                                    && !Objects.equals(expected[i], crypto.decrypt(id, FIELDS[i], actual)))) {
                                throw PaymentCredentialCryptoService.failure("Payment credential migration verification failed");
                            }
                        }
                    }
                }
                return new MigrationResult(records, migratedFields, verifiedEncryptedFields);
            } catch (SQLException | RuntimeException e) {
                throw PaymentCredentialCryptoService.failure("Payment credential migration failed; transaction must roll back");
            }
        });
    }

    /** Counts only; never includes record identities or material. */
    public record MigrationResult(int records, int migratedFields, int verifiedEncryptedFields) { }
}
