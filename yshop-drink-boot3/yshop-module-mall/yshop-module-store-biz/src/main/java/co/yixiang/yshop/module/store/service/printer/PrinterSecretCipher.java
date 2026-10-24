package co.yixiang.yshop.module.store.service.printer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Dedicated externally supplied key. Versioned, randomized authenticated encryption. */
@Component
public class PrinterSecretCipher {
    private final String encodedKey;
    public PrinterSecretCipher(@Value("${yshop.printer.encryption-key:}") String encodedKey) {
        this.encodedKey = encodedKey;
    }
    public String encrypt(String value, String context) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context);
            return "v1:" + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (PrinterFailure ex) { throw ex; }
        catch (Exception ex) { throw new PrinterFailure("SECRET_UNAVAILABLE", false); }
    }
    public String decrypt(String value, String context) {
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(parts[1]), context);
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (PrinterFailure ex) { throw ex; }
        catch (Exception ex) { throw new PrinterFailure("SECRET_UNAVAILABLE", false); }
    }
    private Cipher cipher(int mode, byte[] nonce, String context) throws Exception {
        byte[] key;
        try { key = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException ex) { throw new PrinterFailure("ENCRYPTION_NOT_CONFIGURED", false); }
        if (key.length != 32) throw new PrinterFailure("ENCRYPTION_NOT_CONFIGURED", false);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
