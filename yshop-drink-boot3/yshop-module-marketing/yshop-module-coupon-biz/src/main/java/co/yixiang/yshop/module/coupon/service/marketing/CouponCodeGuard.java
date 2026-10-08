package co.yixiang.yshop.module.coupon.service.marketing;

import co.yixiang.yshop.framework.common.exception.ServiceException;
import co.yixiang.yshop.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Dedicated admission policy: never derive a bucket from codes, keys or request bodies. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CouponCodeGuard {
    private final RateLimiterRedisDAO limiter;

    public void admit(Long uid, String remoteAddress) {
        admit(uid,remoteAddress,false);
    }

    public void admit(Long uid, String remoteAddress, boolean legacyShortInput) {
        if (uid == null || uid <= 0) throw new ServiceException(401, "请先登录");
        if (remoteAddress == null || remoteAddress.isBlank()) unavailable();
        boolean allowed;
        try {
            String member = "coupon-code:member:" + CouponMarketingService.digest(uid.toString());
            // A blocked member cannot consume the shared-network budget indefinitely.
            allowed = limiter.tryAcquireFixedWindow(member + ":minute", 5, 60)
                    && limiter.tryAcquireFixedWindow(member + ":hour", 20, 3600);
            if (allowed) {
                String ip = "coupon-code:ip:" + CouponMarketingService.digest(remoteAddress);
                allowed = limiter.tryAcquireFixedWindow(ip + ":minute", 100, 60)
                        && limiter.tryAcquireFixedWindow(ip + ":hour", 1000, 3600);
                // Compatibility, not an entropy claim. Short historical guesses get a smaller
                // shared daily budget before any code lookup, whether valid or invalid.
                if (allowed && legacyShortInput) {
                    allowed = limiter.tryAcquireFixedWindow(member + ":legacy-day", 3, 86400)
                            && limiter.tryAcquireFixedWindow(ip + ":legacy-day", 30, 86400);
                }
            }
        } catch (RuntimeException unavailable) {
            // Redis exceptions may contain addresses/credentials: do not log the cause or request.
            log.warn("Coupon code admission unavailable; redemption denied");
            unavailable(); return;
        }
        if (!allowed) throw new ServiceException(429, "兑换尝试过于频繁，请稍后重试");
    }

    private static void unavailable() {
        throw new ServiceException(503, "兑换服务暂时不可用，请稍后重试");
    }
}
