package co.yixiang.yshop.module.pay.credential;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class PaymentCredentialCryptoServiceTest {
    static String randomKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return Base64.getEncoder().encodeToString(key); }
    private final PaymentCredentialCryptoService crypto = new PaymentCredentialCryptoService(randomKey());
    private final String secret = "synthetic-test-secret-中文";
    private String encrypted() { return crypto.encrypt("test_merchant", "keyPrivate", secret); }
    @Test void roundtrip() { assertEquals(secret, crypto.decrypt("test_merchant", "keyPrivate", encrypted())); }
    @Test void randomNonce() { assertNotEquals(encrypted(), encrypted()); }
    @Test void wrongMasterKey() { assertThrows(IllegalStateException.class, () -> new PaymentCredentialCryptoService(randomKey()).decrypt("test_merchant", "keyPrivate", encrypted())); }
    @Test void tamper() { byte[] payload = Base64.getDecoder().decode(encrypted().substring(7)); payload[payload.length-1] ^= 1; assertThrows(IllegalStateException.class, () -> crypto.decrypt("test_merchant", "keyPrivate", "enc:v1:"+Base64.getEncoder().encodeToString(payload))); }
    @Test void wrongFieldAad() { assertThrows(IllegalStateException.class, () -> crypto.decrypt("test_merchant", "keyCertPwd", encrypted())); }
    @Test void wrongRecordAad() { assertThrows(IllegalStateException.class, () -> crypto.decrypt("other", "keyPrivate", encrypted())); }
    @Test void legacyFailsClosed() { assertThrows(IllegalStateException.class, () -> crypto.decrypt("test_merchant", "keyPrivate", secret)); }
    @Test void blankIsAbsent() { for (String blank : new String[]{null,"","  "}) { assertNull(crypto.encrypt("test_merchant", "keyPrivate", blank)); assertNull(crypto.decrypt("test_merchant", "keyPrivate", blank)); } }
    @Test void envelopeVersion() { assertTrue(encrypted().startsWith("enc:v1:")); assertFalse(encrypted().contains(secret)); }
    @Test void malformedNeverFallsBack() { for (String invalid : new String[]{"enc:v1:%%%", " enc:v1:AAAA", "enc:v2:AAAA", "enc:v1:AA==", "enc:v1:", "enc:v1: "+secret}) assertThrows(IllegalStateException.class, () -> crypto.decrypt("test_merchant", "keyPrivate", invalid)); }
    @Test void errorsAndToStringContainNoMaterial() { var e=assertThrows(IllegalStateException.class, () -> crypto.decrypt("test_merchant", "keyPrivate", secret)); assertFalse(e.toString().contains(secret)); assertNull(e.getCause()); assertFalse(crypto.toString().contains(secret)); }
    @Test void clientCannotInjectCiphertext() { assertThrows(IllegalStateException.class, () -> crypto.encrypt("test_merchant", "keyPrivate", encrypted())); }
    @Test void missingAndInvalidMasterKeyFailClosed() { assertThrows(IllegalStateException.class, () -> new PaymentCredentialCryptoService("").encrypt("test_merchant", "keyPrivate", secret)); assertThrows(IllegalStateException.class, () -> new PaymentCredentialCryptoService(secret)); assertThrows(IllegalStateException.class, () -> new PaymentCredentialCryptoService(Base64.getEncoder().encodeToString(new byte[16]))); }
    @Test void identityAndFieldValidation() { assertThrows(IllegalStateException.class, () -> crypto.encrypt("bad:id", "keyPrivate", secret)); assertThrows(IllegalStateException.class, () -> crypto.encrypt("test_merchant", "unknown", secret)); }
}
