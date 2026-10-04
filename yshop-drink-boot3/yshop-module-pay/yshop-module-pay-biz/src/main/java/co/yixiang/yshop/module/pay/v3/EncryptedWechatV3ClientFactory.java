package co.yixiang.yshop.module.pay.v3;

import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;
import co.yixiang.yshop.module.pay.credential.PaymentCredentialCryptoService;
import co.yixiang.yshop.module.pay.dal.mysql.merchantdetails.MerchantDetailsMapper;

import com.wechat.pay.java.core.RSAPublicKeyConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class EncryptedWechatV3ClientFactory implements WechatV3ClientFactory {
    private final MerchantDetailsMapper merchants;
    private final PaymentCredentialCryptoService crypto;
    private final boolean enabled;

    public EncryptedWechatV3ClientFactory(
            MerchantDetailsMapper merchants,
            PaymentCredentialCryptoService crypto,
            @Value("${yshop.pay.wechat-v3.enabled:false}") boolean enabled) {
        this.merchants = merchants;
        this.crypto = crypto;
        this.enabled = enabled;
    }

    @TenantIgnore
    public WechatV3Client forMerchant(String id) {
        if (!enabled) throw new IllegalStateException("WECHAT_V3_DISABLED");
        try {
            var merchant = merchants.selectById(id);
            if (merchant == null
                    || !"wxPay".equals(merchant.getPayType())
                    || !"V3".equals(merchant.getWechatApiVersion())
                    || !Integer.valueOf(0).equals(merchant.getIsTest())
                    || Boolean.TRUE.equals(merchant.getDeleted())) throw unavailable();
            for (String value :
                    new String[] {
                        merchant.getAppid(),
                        merchant.getMchId(),
                        merchant.getMerchantCertificateSerial(),
                        merchant.getPlatformPublicKeyId(),
                        merchant.getKeyPublic()
                    }) if (PaymentCredentialCryptoService.blank(value)) throw unavailable();
            URI url = URI.create(merchant.getNotifyUrl());
            if (!"https".equals(url.getScheme())
                    || url.getHost() == null
                    || url.getUserInfo() != null
                    || url.getQuery() != null
                    || url.getFragment() != null
                    || !("/app-api/order/notify/wechat-v3/" + id).equals(url.getPath()))
                throw unavailable();
            // No auto-certificate downloader and no SDK/client cache. Decrypt only at runtime
            // build.
            String privateKey = crypto.decrypt(id, "keyPrivate", merchant.getKeyPrivate()),
                    apiKey = crypto.decrypt(id, "apiV3Key", merchant.getApiV3Key());
            if (PaymentCredentialCryptoService.blank(privateKey)
                    || apiKey == null
                    || apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 32)
                throw unavailable();
            RSAPublicKeyConfig config =
                    new RSAPublicKeyConfig.Builder()
                            .merchantId(merchant.getMchId())
                            .merchantSerialNumber(merchant.getMerchantCertificateSerial())
                            .privateKey(privateKey)
                            .publicKey(merchant.getKeyPublic())
                            .publicKeyId(merchant.getPlatformPublicKeyId())
                            .apiV3Key(apiKey)
                            .build();
            return new OfficialWechatV3Client(
                    merchant.getAppid(),
                    merchant.getMchId(),
                    merchant.getNotifyUrl(),
                    config,
                    null);
        } catch (RuntimeException ignored) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("WECHAT_V3_MERCHANT_UNAVAILABLE");
    }

    public String toString() {
        return "EncryptedWechatV3ClientFactory[material omitted]";
    }
}
