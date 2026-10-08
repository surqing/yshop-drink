package co.yixiang.yshop.module.order.ordering;

import co.yixiang.yshop.framework.common.exception.ServiceException;
import co.yixiang.yshop.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.module.coupon.controller.app.coupon.AppCouponController;
import co.yixiang.yshop.module.coupon.controller.app.coupon.vo.AppReceVO;
import co.yixiang.yshop.module.coupon.service.coupon.AppCouponService;
import co.yixiang.yshop.module.coupon.service.couponuser.AppCouponUserService;
import co.yixiang.yshop.module.coupon.service.marketing.CouponCodeGuard;
import co.yixiang.yshop.module.coupon.service.marketing.CouponMarketingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;

class CouponCodeSecurityTest {
    RateLimiterRedisDAO redis;
    AppCouponService service;
    AppCouponController controller;
    MockHttpServletRequest request;
    static void login(long uid) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser().setId(uid).setUserType(1),null,List.of()));
    }
    static AppReceVO body(String code,String key) {
        var b=new AppReceVO();b.setCode(code);b.setRequestKey(key);return b;
    }
    @BeforeEach void setup() {
        redis=mock(RateLimiterRedisDAO.class);service=mock(AppCouponService.class);
        when(redis.tryAcquireFixedWindow(anyString(),anyInt(),anyInt())).thenReturn(true);
        controller=new AppCouponController(mock(AppCouponUserService.class),service,new CouponCodeGuard(redis));
        request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.10");login(1);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void changingCodeAndKeyNeverChangesBucketsAndInvalidAttemptsCount() {
        doThrow(new ServiceException(1006002090,"COUPON_CODE_INVALID")).when(service).receive(anyLong(),isNull(),anyString(),anyString());
        for(int i=0;i<20;i++) assertEquals(400,assertThrows(ServiceException.class,()->controller.receive(body(UUID.randomUUID().toString(),UUID.randomUUID().toString()),request)).getCode());
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis,times(80)).tryAcquireFixedWindow(keys.capture(),anyInt(),anyInt());
        assertEquals(4,new HashSet<>(keys.getAllValues()).size());
        for(String k:keys.getAllValues()){assertFalse(k.contains("192.0.2.10"));assertFalse(k.contains("requestKey"));assertTrue(k.matches("coupon-code:(member|ip):[a-f0-9]{64}:(minute|hour)"));}
    }
    @Test void throttlePrecedesProviderDatabaseAndIgnoresForwardedHeaders() {
        when(redis.tryAcquireFixedWindow(anyString(),eq(5),eq(60))).thenReturn(false);
        for(int i=0;i<20;i++) {request.addHeader("X-Forwarded-For","198.51.100."+i);assertEquals(429,assertThrows(ServiceException.class,()->controller.receive(body("wrong-code",UUID.randomUUID().toString()),request)).getCode());}
        verifyNoInteractions(service);
        verify(redis,times(20)).tryAcquireFixedWindow("coupon-code:member:"+CouponMarketingService.digest("1")+":minute",5,60);
        verifyNoMoreInteractions(redis);
    }
    @Test void sharedNetworkHasIndependentMemberBuckets() {
        for(long uid=1;uid<=20;uid++){login(uid);assertTrue(controller.receive(body("synthetic-code","retry-key"),request).getData());}
        verify(service,times(20)).receive(anyLong(),isNull(),eq("synthetic-code"),eq("retry-key"));
        verify(redis,times(20)).tryAcquireFixedWindow("coupon-code:ip:"+CouponMarketingService.digest("192.0.2.10")+":minute",100,60);
    }
    @Test void ordinaryPublicClaimsDoNotConsumeCodeBudget() {
        var b=new AppReceVO();b.setId(1L);b.setRequestKey("public-key");controller.receive(b,request);verifyNoInteractions(redis);
    }
    @Test void sameRetryKeyIsPassedUnchangedToExistingIdempotency() {
        for(int i=0;i<2;i++)controller.receive(body("synthetic-code","same-retry-key"),request);
        verify(service,times(2)).receive(1L,null,"synthetic-code","same-retry-key");
    }
    @ParameterizedTest @ValueSource(strings={"COUPON_CODE_INVALID","COUPON_DISABLED","COUPON_CLAIM_NOT_STARTED","COUPON_SOLD_OUT","COUPON_USER_LIMIT","COUPON_NOT_EXISTS","COUPON_IDEMPOTENCY_CONFLICT"})
    void hiddenActivitiesHaveSameFailureResponse(String internal) {
        doThrow(new ServiceException(1006002090,internal)).when(service).receive(anyLong(),isNull(),anyString(),anyString());
        var error=assertThrows(ServiceException.class,()->controller.receive(body("synthetic-code","retry-key"),request));
        assertEquals(400,error.getCode());assertEquals("兑换码无效或当前不可兑换，请检查兑换码及活动规则",error.getMessage());
    }
    @Test void emptyOrMalformedCodesCannotBypassAdmission() {
        controller.receive(body("","retry-key"),request);
        var b=body("bad-code","retry-key");b.setId(1L);controller.receive(b,request);
        verify(redis,times(12)).tryAcquireFixedWindow(anyString(),anyInt(),anyInt());
    }
    @Test void anonymousCannotConsumeOrRedeem() {
        SecurityContextHolder.clearContext();assertEquals(401,assertThrows(ServiceException.class,()->controller.receive(body("synthetic-code","retry-key"),request)).getCode());verifyNoInteractions(redis,service);
    }
    @Test void redisFailureDoesNotFailOpenOrLogRequestAndExceptionPayload() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CouponCodeGuard.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            when(redis.tryAcquireFixedWindow(anyString(),anyInt(),anyInt())).thenThrow(new IllegalStateException("synthetic-sensitive-code private-redis-url"));
            assertEquals(503,assertThrows(ServiceException.class,()->controller.receive(body("synthetic-sensitive-code","sensitive-request-key"),request)).getCode());
            verifyNoInteractions(service);assertFalse(appender.list.isEmpty());
            for(var event:appender.list){assertFalse(event.getFormattedMessage().contains("synthetic-sensitive-code"));assertFalse(event.getFormattedMessage().contains("private-redis-url"));assertFalse(event.getFormattedMessage().contains("sensitive-request-key"));assertNull(event.getThrowableProxy());}
        } finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void generatedCodesUseRandomBytesAndAreUnique() {
        var seen=new HashSet<String>();for(int i=0;i<1000;i++){String code=CouponMarketingService.generateCode();assertEquals(24,Base64.getUrlDecoder().decode(code).length);assertTrue(seen.add(code));}
        // Uniqueness is a regression check, not an entropy proof: SecureRandom bytes supply entropy.
    }
    @Test void requestToStringAndCreationResultAreRedacted() {
        assertFalse(body("synthetic-sensitive-code","sensitive-request-key").toString().contains("synthetic-sensitive-code"));
        assertFalse(new co.yixiang.yshop.module.coupon.service.coupon.CouponService.CodeCreation(1L,"synthetic-sensitive-code").toString().contains("synthetic-sensitive-code"));
    }
    @Test void existingAspectNeverReadsOrLogsArguments() throws Exception {
        var aspect=new co.yixiang.yshop.framework.ratelimiter.core.aop.RateLimiterAspect(List.of(new FixedResolver()),redis);
        var point=mock(org.aspectj.lang.JoinPoint.class);var signature=mock(org.aspectj.lang.reflect.MethodSignature.class);
        when(point.getSignature()).thenReturn(signature);when(signature.toShortString()).thenReturn("Coupon.receive()");
        when(signature.toLongString()).thenReturn("Coupon.receive()");when(signature.getMethod()).thenReturn(getClass().getDeclaredMethod("limited"));
        when(redis.tryAcquire(anyString(),anyInt(),anyInt(),any())).thenReturn(false);
        assertThrows(ServiceException.class,()->aspect.beforePointCut(point,getClass().getDeclaredMethod("limited").getAnnotation(co.yixiang.yshop.framework.ratelimiter.core.annotation.RateLimiter.class)));
        verify(point,never()).getArgs();
    }
    public static class FixedResolver implements co.yixiang.yshop.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver {
        public String resolver(org.aspectj.lang.JoinPoint point,co.yixiang.yshop.framework.ratelimiter.core.annotation.RateLimiter annotation){return "synthetic-fixed";}
    }
    @co.yixiang.yshop.framework.ratelimiter.core.annotation.RateLimiter(keyResolver=FixedResolver.class) void limited(){}
}
