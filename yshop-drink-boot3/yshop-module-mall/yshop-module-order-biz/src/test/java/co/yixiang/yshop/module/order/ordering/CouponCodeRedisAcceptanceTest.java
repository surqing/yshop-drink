package co.yixiang.yshop.module.order.ordering;

import co.yixiang.yshop.framework.common.exception.ServiceException;
import co.yixiang.yshop.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import co.yixiang.yshop.module.coupon.controller.app.coupon.AppCouponController;
import co.yixiang.yshop.module.coupon.service.coupon.AppCouponService;
import co.yixiang.yshop.module.coupon.service.couponuser.AppCouponUserService;
import co.yixiang.yshop.module.coupon.service.marketing.CouponCodeGuard;
import co.yixiang.yshop.module.coupon.service.marketing.CouponMarketingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Redis, two independent clients/workers; delete only this run's random test namespace. */
@EnabledIfEnvironmentVariable(named="YSHOP_COUPON_REDIS_ACCEPTANCE",matches="true")
class CouponCodeRedisAcceptanceTest {
    static RedissonClient first,second;
    String namespace;
    RateLimiterRedisDAO a,b;
    AppCouponService service;
    AppCouponController one,two;
    @BeforeAll static void connect() throws Exception {
        var settings=new Properties();try(var reader=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(System.getenv("YSHOP_COUPON_REDIS_CONFIG")))){settings.load(reader);}
        if(!"redis://127.0.0.1:6379".equals(settings.getProperty("url")))throw new IllegalArgumentException("LOOPBACK_REDIS_REQUIRED");
        Config config=new Config();config.useSingleServer().setAddress(settings.getProperty("url")).setPassword(settings.getProperty("password"))
                .setTimeout(1500).setConnectTimeout(2000).setRetryAttempts(0).setConnectionPoolSize(4).setConnectionMinimumIdleSize(1);
        first=Redisson.create(config);second=Redisson.create(config);
    }
    @AfterAll static void stop(){if(first!=null)first.shutdown();if(second!=null)second.shutdown();}
    RateLimiterRedisDAO namespaced(RedissonClient client) {
        return new RateLimiterRedisDAO(client) {
            @Override public boolean tryAcquireFixedWindow(String key,int count,int seconds){return super.tryAcquireFixedWindow(namespace+key,count,seconds);}
        };
    }
    @BeforeEach void setup() {
        namespace="accept-coupon-code:"+UUID.randomUUID()+":";a=namespaced(first);b=namespaced(second);
        service=mock(AppCouponService.class);
        one=new AppCouponController(mock(AppCouponUserService.class),service,new CouponCodeGuard(a));
        two=new AppCouponController(mock(AppCouponUserService.class),service,new CouponCodeGuard(b));
    }
    @AfterEach void cleanup() {
        first.getKeys().deleteByPattern("rate_limiter:"+namespace+"*");SecurityContextHolder.clearContext();
    }
    MockHttpServletRequest request() {var r=new MockHttpServletRequest();r.setRemoteAddr("192.0.2.20");return r;}
    List<Integer> concurrent(boolean sameCode, boolean sameMember) throws Exception {
        return concurrent(sameCode,sameMember,false);
    }
    List<Integer> concurrent(boolean sameCode, boolean sameMember, boolean fullLength) throws Exception {
        var pool=Executors.newFixedThreadPool(20);var ready=new CountDownLatch(20);var go=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();
        try {
            for(int i=0;i<20;i++){final int n=i;futures.add(pool.submit(()->{
                CouponCodeSecurityTest.login(sameMember?1:n+1);ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));
                try {(n%2==0?one:two).receive(CouponCodeSecurityTest.body(fullLength?String.format("%032d",n):sameCode?"wrong-code":"wrong-code-"+n,UUID.randomUUID().toString()),request());return 200;}
                catch(ServiceException e){return e.getCode();}finally{SecurityContextHolder.clearContext();}
            }));}
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();var results=new ArrayList<Integer>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));return results;
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @RepeatedTest(20) void twentyDifferentWrongCodesAndKeysAcrossTwoInstances() throws Exception {
        doThrow(new ServiceException(1006002090,"COUPON_CODE_INVALID")).when(service).receive(anyLong(),isNull(),anyString(),anyString());
        var results=concurrent(false,true);assertEquals(3,Collections.frequency(results,400));assertEquals(17,Collections.frequency(results,429));
        verify(service,times(3)).receive(anyLong(),isNull(),anyString(),anyString());
    }
    @RepeatedTest(20) void rotatingOnlyRequestKeysCannotBypass() throws Exception {
        doThrow(new ServiceException(1006002090,"COUPON_CODE_INVALID")).when(service).receive(anyLong(),isNull(),anyString(),anyString());
        var results=concurrent(true,true);assertEquals(3,Collections.frequency(results,400));assertEquals(17,Collections.frequency(results,429));
    }
    @RepeatedTest(20) void fullLengthPredictableGuessesStillShareMemberLimit() throws Exception {
        doThrow(new ServiceException(1006002090,"COUPON_CODE_INVALID")).when(service).receive(anyLong(),isNull(),anyString(),anyString());
        var results=concurrent(false,true,true);assertEquals(5,Collections.frequency(results,400));assertEquals(15,Collections.frequency(results,429));
    }
    @Test void twentyMembersNormallyRedeemBehindOneNat() throws Exception {
        assertEquals(20,Collections.frequency(concurrent(true,false),200));verify(service,times(20)).receive(anyLong(),isNull(),anyString(),anyString());
    }
    @Test void hourlyBudgetSurvivesMinuteExpiryAndOtherInstances() {
        var ga=new CouponCodeGuard(a);var gb=new CouponCodeGuard(b);
        String minute="rate_limiter:"+namespace+"coupon-code:member:"+CouponMarketingService.digest("1")+":minute";
        for(int i=0;i<20;i++){first.getKeys().delete(minute);(i%2==0?ga:gb).admit(1L,"192.0.2.20");}
        first.getKeys().delete(minute);assertEquals(429,assertThrows(ServiceException.class,()->ga.admit(1L,"192.0.2.20")).getCode());
    }
    @Test void ipLimitCannotBeBypassedByChangingMembersOrForwardedHeader() {
        var g=new CouponCodeGuard(a);for(long uid=1;uid<=100;uid++)g.admit(uid,"192.0.2.20");
        assertEquals(429,assertThrows(ServiceException.class,()->new CouponCodeGuard(b).admit(101L,"192.0.2.20")).getCode());
        new CouponCodeGuard(b).admit(102L,"198.51.100.20");
    }
    @Test void legacyShortCodeHasSharedDailyQuotaWithoutDisablingCompatibility() {
        var ga=new CouponCodeGuard(a);var gb=new CouponCodeGuard(b);
        for(long uid=1;uid<=30;uid++)ga.admit(uid,"192.0.2.20",true);
        assertEquals(429,assertThrows(ServiceException.class,()->gb.admit(31L,"192.0.2.20",true)).getCode());
        for(int i=0;i<3;i++)ga.admit(40L,"198.51.100.20",true);
        assertEquals(429,assertThrows(ServiceException.class,()->gb.admit(40L,"198.51.100.20",true)).getCode());
        // New random-code input does not consume the additional legacy-short budget.
        gb.admit(40L,"198.51.100.20",false);
    }
    @Test void actualClientShutdownFailsClosedWithoutClaim() {
        RedissonClient lost=Redisson.create(new Config(first.getConfig()));
        try {
            var dao=namespaced(lost);
            var controller=new AppCouponController(mock(AppCouponUserService.class),service,new CouponCodeGuard(dao));
            lost.shutdown();
            CouponCodeSecurityTest.login(1L);
            assertEquals(503,assertThrows(ServiceException.class,()->controller.receive(
                    CouponCodeSecurityTest.body("synthetic-client-outage-code",UUID.randomUUID().toString()),request())).getCode());
            verifyNoInteractions(service);
        } finally { if(!lost.isShutdown())lost.shutdown(); }
    }
    @Test void naturalWindowExpiryAllowsBoundedRetryAndRejectedCallsDoNotExtendTtl() throws Exception {
        assertTrue(a.tryAcquireFixedWindow("expiry",1,1));long ttl=first.getBucket("rate_limiter:"+namespace+"expiry").remainTimeToLive();
        for(int i=0;i<20;i++)assertFalse(b.tryAcquireFixedWindow("expiry",1,1));
        assertTrue(first.getBucket("rate_limiter:"+namespace+"expiry").remainTimeToLive()<=ttl);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(first.getBucket("rate_limiter:"+namespace+"expiry").isExists() && System.nanoTime()<deadline)
            Thread.sleep(20); // Real Redis server TTL cannot be advanced with the application Clock.
        assertFalse(first.getBucket("rate_limiter:"+namespace+"expiry").isExists());
        assertTrue(b.tryAcquireFixedWindow("expiry",1,1));
    }
}
