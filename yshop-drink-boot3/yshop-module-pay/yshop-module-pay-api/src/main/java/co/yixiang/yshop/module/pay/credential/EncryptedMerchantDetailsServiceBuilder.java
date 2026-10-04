package co.yixiang.yshop.module.pay.credential;

import com.egzosn.pay.common.bean.CertStoreType;
import com.egzosn.pay.spring.boot.core.builders.MerchantDetailsServiceBuilder;
import com.egzosn.pay.spring.boot.core.merchant.MerchantDetailsService;
import com.egzosn.pay.spring.boot.core.merchant.PaymentPlatformMerchantDetails;
import com.egzosn.pay.spring.boot.core.merchant.bean.CommonPaymentPlatformMerchantDetails;
import com.egzosn.pay.spring.boot.core.provider.InMemoryMerchantDetailsManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads ciphertext afresh on each SDK lookup. Plaintext exists only in the SDK object. */
public final class EncryptedMerchantDetailsServiceBuilder extends MerchantDetailsServiceBuilder {
    private final JdbcTemplate jdbc;
    private final PaymentCredentialCryptoService crypto;

    public EncryptedMerchantDetailsServiceBuilder(JdbcTemplate jdbc, PaymentCredentialCryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    protected MerchantDetailsService performBuild() {
        return id -> {
            try {
                var rows = jdbc.query("SELECT * FROM merchant_details WHERE details_id = ? AND deleted = 0",
                        (rs, index) -> runtime(rs), id);
                if (rows.size() != 1) throw PaymentCredentialCryptoService.failure("Payment merchant configuration unavailable");
                var merchant = rows.get(0);
                MessageConfigurerBridge.configure(merchant, configurer);
                return merchant;
            } catch (RuntimeException e) {
                // SDK certificate parsing errors may include material or a private path.
                throw PaymentCredentialCryptoService.failure("Payment merchant runtime configuration failed");
            }
        };
    }

    private ServerOnlyMerchantDetails runtime(ResultSet rs) throws SQLException {
        if("wxPay".equals(rs.getString("pay_type")) && "V3".equals(rs.getString("wechat_api_version")))
            throw PaymentCredentialCryptoService.failure("Legacy WeChat merchant runtime is disabled for V3");
        var merchant = new ServerOnlyMerchantDetails();
        String id = rs.getString("details_id");
        merchant.setDetailsId(id);
        merchant.setAppId(rs.getString("appid"));
        merchant.setPayType(rs.getString("pay_type"));
        merchant.setMchId(rs.getString("mch_id"));
        String type = rs.getString("cert_store_type");
        if (!PaymentCredentialCryptoService.blank(type)) merchant.setCertStoreType(CertStoreType.valueOf(type));
        String privateKey = crypto.decrypt(id, "keyPrivate", rs.getString("key_private"));
        String certificate = crypto.decrypt(id, "keyCert", rs.getString("key_cert"));
        String password = crypto.decrypt(id, "keyCertPwd", rs.getString("key_cert_pwd"));
        String publicKey = rs.getString("key_public");
        if (merchant.getCertStoreType() == CertStoreType.INPUT_STREAM) {
            if ("unionPay".equals(merchant.getPayType())) {
                merchant.setKeystore(stream(privateKey));
                merchant.setKeyPublicCert(stream(publicKey));
            } else {
                merchant.setKeyPrivate(privateKey);
                merchant.setKeyPublic(publicKey);
            }
            merchant.setKeyCert(stream(certificate));
        } else {
            merchant.setKeystore(privateKey);
            merchant.setKeyPublicCert(publicKey);
            merchant.setKeyCert(certificate);
        }
        merchant.setKeystorePwd(password);
        merchant.setNotifyUrl(rs.getString("notify_url"));
        merchant.setReturnUrl(rs.getString("return_url"));
        merchant.setSignType(rs.getString("sign_type"));
        merchant.setSeller(rs.getString("seller"));
        merchant.setSubAppId(rs.getString("sub_app_id"));
        merchant.setSubMchId(rs.getString("sub_mch_id"));
        merchant.setInputCharset(rs.getString("input_charset"));
        merchant.setTest(rs.getBoolean("is_test"));
        merchant.initService(); // Constructs the existing Wx/Alipay SDK; sends no payment request.
        return merchant;
    }

    private static ByteArrayInputStream stream(String value) {
        return value == null ? null : new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Access to the SDK's protected handler wiring, without an in-memory merchant cache. */
    private static final class MessageConfigurerBridge extends InMemoryMerchantDetailsManager {
        static void configure(PaymentPlatformMerchantDetails merchant,
                              com.egzosn.pay.spring.boot.core.configurers.PayMessageConfigurer configurer) {
            setPayMessageConfigurer(merchant.getPayService(), merchant, configurer);
        }
    }

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = RuntimeSerializer.class)
    public static final class ServerOnlyMerchantDetails extends CommonPaymentPlatformMerchantDetails {
        @Override public java.io.InputStream getKeyCertInputStream() throws java.io.IOException {
            try { return super.getKeyCertInputStream(); }
            catch (java.io.IOException e) { throw PaymentCredentialCryptoService.failure("Payment certificate loading failed"); }
        }
        @Override public java.io.InputStream getKeystoreInputStream() throws java.io.IOException {
            try { return super.getKeystoreInputStream(); }
            catch (java.io.IOException e) { throw PaymentCredentialCryptoService.failure("Payment certificate loading failed"); }
        }
        @Override public java.io.InputStream getKeyPublicCertInputStream() throws java.io.IOException {
            try { return super.getKeyPublicCertInputStream(); }
            catch (java.io.IOException e) { throw PaymentCredentialCryptoService.failure("Payment certificate loading failed"); }
        }
        @Override public String toString() { return "PaymentRuntime[credential material omitted]"; }
    }

    /** Defends against accidental serialization of the runtime object, including inherited aliases. */
    public static final class RuntimeSerializer extends com.fasterxml.jackson.databind.JsonSerializer<ServerOnlyMerchantDetails> {
        @Override public void serialize(ServerOnlyMerchantDetails value, com.fasterxml.jackson.core.JsonGenerator gen,
                                       com.fasterxml.jackson.databind.SerializerProvider serializers) throws java.io.IOException {
            gen.writeStartObject();
            gen.writeBooleanField("serverOnlyPaymentRuntime", true);
            gen.writeEndObject();
        }
    }
}
