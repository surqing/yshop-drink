package co.yixiang.yshop.module.pay.v3;

/** Lazy SDK creation; implementations must never log or cache plaintext merchant credentials. */
public interface WechatV3ClientFactory {
    WechatV3Client forMerchant(String merchantDetailsId);
}
