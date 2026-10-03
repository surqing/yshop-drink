package co.yixiang.yshop.module.pay.credential;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/** Versioned, authenticated storage encryption. No input or crypto cause is exposed in errors. */
@Component
public final class PaymentCredentialCryptoService {
    public static final String PREFIX = "enc:v1:";
    private static final Set<String> FIELDS = Set.of("keyPrivate", "keyCertPwd", "keyCert");
    private static final int NONCE_BYTES = 12;
    private static final int MAX_TEXT_BYTES = 262144;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PaymentCredentialCryptoService(
            @Value("${yshop.pay.credentials.master-key:${YSHOP_PAYMENT_CREDENTIAL_MASTER_KEY:}}") String encodedKey) {
        if (blank(encodedKey)) {
            key = null; // Metadata reads remain available; credential operations fail closed.
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException e) {
            throw failure("Payment credential master key configuration is invalid");
        }
        try {
            if (decoded.length != 32) {
                throw failure("Payment credential master key must contain 32 bytes");
            }
            key = new SecretKeySpec(decoded, "AES");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    /** Incoming client values must be plaintext; encrypted material cannot be transplanted. */
    public String encrypt(String detailsId, String field, String plaintext) {
        if (blank(plaintext)) return null;
        if (plaintext.trim().startsWith("enc:")) throw failure("Encrypted credential input is not accepted");
        requireKey();
        byte[] aad = aad(detailsId, field);
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw failure("Payment credential exceeds storage limit");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(bytes);
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw failure("Payment credential encryption failed");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Runtime accepts authenticated storage envelopes only, never a legacy fallback. */
    public String decrypt(String detailsId, String field, String stored) {
        if (blank(stored)) return null;
        requireKey();
        byte[] aad = aad(detailsId, field);
        if (!stored.startsWith(PREFIX) || stored.length() > (MAX_TEXT_BYTES + 28) * 4 / 3 + 16) {
            throw failure("Payment credential format is invalid; legacy values require explicit migration");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw failure("Payment credential format is invalid");
        }
        if (payload.length < NONCE_BYTES + 16 || payload.length > MAX_TEXT_BYTES + 28) {
            throw failure("Payment credential format is invalid");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, payload, 0, NONCE_BYTES));
            cipher.updateAAD(aad);
            byte[] plaintext = cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw failure("Payment credential authentication failed");
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    public void requireKey() {
        if (key == null) throw failure("Payment credential master key is not configured");
    }

    public static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private byte[] aad(String id, String field) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || !FIELDS.contains(field)) {
            throw failure("Payment credential binding is invalid");
        }
        return ("merchant_details:" + id + ":" + field).getBytes(StandardCharsets.UTF_8);
    }

    public static IllegalStateException failure(String safeMessage) {
        return new IllegalStateException(safeMessage); // Deliberately excludes input and cause.
    }

    @Override
    public String toString() { return "PaymentCredentialCryptoService[material omitted]"; }
}
