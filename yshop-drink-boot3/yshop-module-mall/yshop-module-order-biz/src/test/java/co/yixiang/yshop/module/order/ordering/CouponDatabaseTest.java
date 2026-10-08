package co.yixiang.yshop.module.order.ordering;

import static org.junit.jupiter.api.Assertions.*;
import co.yixiang.yshop.module.coupon.service.marketing.*;
import co.yixiang.yshop.module.coupon.service.coupon.*;
import co.yixiang.yshop.module.coupon.service.couponuser.*;
import co.yixiang.yshop.module.coupon.dal.mysql.coupon.CouponMapper;
import co.yixiang.yshop.module.coupon.dal.mysql.couponuser.CouponUserMapper;
import co.yixiang.yshop.module.coupon.controller.admin.coupon.vo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.mybatis.spring.SqlSessionTemplate;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Production claim/rights/admin/order services, on disposable H2 or MySQL8/InnoDB. */
class CouponDatabaseTest {
    OrderingDatabaseTest f;
    CouponMarketingService marketing;
    CouponServiceImpl templates;
    CouponUserServiceImpl instances;
    LocalDateTime now=LocalDateTime.of(2026,10,8,12,0);
    <T> T proxy(T object) {var p=new ProxyFactory(object);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(f.tm,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    void field(Object o,String n,Object value){ReflectionTestUtils.setField(o,n,value);}
    @BeforeEach void setup() throws Exception {
        f=new OrderingDatabaseTest();f.setup();
        for(String t:List.of("yshop_coupon_claim","yshop_coupon_newcomer","yshop_coupon_operation","yshop_coupon","yshop_coupon_user"))f.db.execute("DROP TABLE IF EXISTS "+t);
        String seed=Files.readString(f.boot.resolve("sql/yixiang-drink-open.sql"));
        for(String t:List.of("yshop_coupon","yshop_coupon_user")) {
            var m=java.util.regex.Pattern.compile("CREATE TABLE `"+t+"`.*?;",java.util.regex.Pattern.DOTALL).matcher(seed);assertTrue(m.find());
            String ddl=m.group();if(!f.mysql)ddl=ddl.replaceAll("ENGINE=InnoDB.*?;",";").replace("bit(1)","BOOLEAN").replace("b'0'","FALSE").replace(" USING BTREE","");f.db.execute(ddl);
        }
        f.db.execute("ALTER TABLE yshop_coupon_user ADD COLUMN reserved_order_id VARCHAR(32)");
        if(f.mysql) new TransactionTemplate(f.tm).executeWithoutResult(s->{try{for(String p:Files.readString(f.boot.resolve("sql/migrations/2026-10-08-coupon-marketing.sql")).split(";"))if(!p.isBlank())f.db.execute(p);}catch(Exception e){throw new RuntimeException(e);}});
        else {
            for(String c:List.of("template_version BIGINT DEFAULT 0","claim_start_time TIMESTAMP","claim_end_time TIMESTAMP","coupon_kind VARCHAR(16) DEFAULT 'REGULAR'","claim_mode VARCHAR(16) DEFAULT 'PUBLIC'","redemption_code_hash CHAR(64) UNIQUE"))f.db.execute("ALTER TABLE yshop_coupon ADD COLUMN "+c);
            for(String c:List.of("template_version BIGINT DEFAULT 0","redeemed_order_id VARCHAR(32)","redeemed_at TIMESTAMP","invalid_reason VARCHAR(200)"))f.db.execute("ALTER TABLE yshop_coupon_user ADD COLUMN "+c);
            String ddl=Files.readString(f.boot.resolve("sql/migrations/2026-10-08-coupon-marketing.sql"));
            for(String table:List.of("yshop_coupon_claim","yshop_coupon_newcomer","yshop_coupon_operation")){
                var m=java.util.regex.Pattern.compile("CREATE TABLE IF NOT EXISTS "+table+".*?;",java.util.regex.Pattern.DOTALL).matcher(ddl);assertTrue(m.find());f.db.execute(m.group().replace("CHARACTER SET ascii COLLATE ascii_bin","").replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",""));
            }
            f.db.execute("ALTER TABLE yshop_user ADD COLUMN create_time TIMESTAMP");
        }
        // Base fixture has no successful receipts; add the real lifecycle evidence column.
        f.db.execute("ALTER TABLE yshop_order_payment ADD COLUMN status VARCHAR(32)");
        for(int i=1;i<=25;i++){
            if(i>2)f.db.update("INSERT INTO yshop_user(id,username,password,nickname,create_time) VALUES(?,?,'','',?)",i,"synthetic-"+i,now.minusMinutes(30));
            else f.db.update("UPDATE yshop_user SET create_time=? WHERE id=?",now.minusMinutes(30),i);
        }
        coupon(1,"1",100,1);coupon(2,"2",100,1);coupon(3,"0",100,1);coupon(4,"1,2",100,3);
        var access=f.access(103,false);marketing=proxy(new CouponMarketingService(f.db,access));field(marketing,"clock",Clock.fixed(now.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneId.of("Asia/Shanghai")));
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(f.data);var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var global=com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();global.getDbConfig().setIdType(com.baomidou.mybatisplus.annotation.IdType.AUTO);global.setMetaObjectHandler(new co.yixiang.yshop.framework.mybatis.core.handler.DefaultDBFieldHandler());factory.setGlobalConfig(global);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);
        config.addMapper(CouponMapper.class);config.addMapper(CouponUserMapper.class);factory.setConfiguration(config);var sql=new SqlSessionTemplate(factory.getObject());
        templates=new CouponServiceImpl();field(templates,"mapper",sql.getMapper(CouponMapper.class));field(templates,"marketing",marketing);templates=proxy(templates);
        instances=new CouponUserServiceImpl();field(instances,"mapper",sql.getMapper(CouponUserMapper.class));field(instances,"marketing",marketing);field(instances,"jdbc",f.db);
    }
    @AfterEach void clear(){if(f!=null)f.clearSecurity();}
    void coupon(long id,String shops,int total,int limit){f.db.update("INSERT INTO yshop_coupon(id,shop_id,shop_name,title,is_switch,least,`value`,start_time,end_time,claim_start_time,claim_end_time,create_time,update_time,distribute,`limit`,instructions,coupon_kind,claim_mode,template_version) VALUES(?,?,?,'Synthetic coupon',1,0,0.10,'2020-01-01','2030-01-01','2026-10-08','2030-01-01',?,?,?,?,'Synthetic rights','REGULAR','PUBLIC',1)",id,shops,"Synthetic scope",now.minusHours(1),now,total,limit);}
    String key(){return UUID.randomUUID().toString();}
    long claim(long uid,long coupon){return marketing.claim(uid,coupon,null,key());}
    long n(String sql,Object...args){return f.count(sql,args);}
    co.yixiang.yshop.module.order.controller.app.order.param.AppOrderParam request(long instance){var p=f.request();p.setCouponId(Long.toString(instance));return p;}
    Map<String,Object> row(long id){var r=f.db.queryForMap("SELECT *, (type+0) AS coupon_type FROM yshop_coupon_user WHERE id=?",id);r.put("type",r.get("coupon_type"));return r;}
    @Test void claimCopiesRightsAndSameRequestReturnsOneInstance(){String k=key();long id=marketing.claim(1,1L,null,k);assertEquals(id,marketing.claim(1,1L,null,k));assertEquals(1,n("SELECT receive FROM yshop_coupon WHERE id=1"));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_claim"));assertEquals("AVAILABLE",marketing.state(row(id)));assertNull(row(id).get("exchange_code"));}
    @Test void reusedKeyForDifferentTemplateRejected(){String k=key();marketing.claim(1,1L,null,k);assertThrows(RuntimeException.class,()->marketing.claim(1,3L,null,k));assertEquals(0,n("SELECT receive FROM yshop_coupon WHERE id=3"));}
    @ParameterizedTest @ValueSource(strings={"disabled","future","expired","points","negative","precision","countMismatch","zeroLimit","malformedScope"})
    void eligibilityFailClosed(String why){switch(why){case "disabled"->f.db.update("UPDATE yshop_coupon SET is_switch=0 WHERE id=1");case "future"->f.db.update("UPDATE yshop_coupon SET claim_start_time=? WHERE id=1",now.plusMinutes(1));case "expired"->f.db.update("UPDATE yshop_coupon SET claim_end_time=? WHERE id=1",now);case "points"->f.db.update("UPDATE yshop_coupon SET score=1 WHERE id=1");case "negative"->f.db.update("UPDATE yshop_coupon SET `value`=-1 WHERE id=1");case "precision"->f.db.update("UPDATE yshop_coupon SET `value`=0 WHERE id=1");case "countMismatch"->f.db.update("UPDATE yshop_coupon SET receive=7 WHERE id=1");case "zeroLimit"->f.db.update("UPDATE yshop_coupon SET `limit`=0 WHERE id=1");default->f.db.update("UPDATE yshop_coupon SET shop_id='0,1' WHERE id=1");}assertThrows(RuntimeException.class,()->claim(1,1));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_user"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_claim"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_operation"));}
    @Test void everyHistoricalInstanceCountsTowardLimit(){long id=claim(1,1);f.db.update("UPDATE yshop_coupon_user SET status=1,deleted=1 WHERE id=?",id);assertThrows(RuntimeException.class,()->claim(1,1));assertEquals(1,n("SELECT receive FROM yshop_coupon WHERE id=1"));}
    @Test void limitNThenRejectDifferentKeys(){for(int i=0;i<3;i++)claim(1,4);assertThrows(RuntimeException.class,()->claim(1,4));assertEquals(3,n("SELECT COUNT(*) FROM yshop_coupon_user WHERE user_id=1 AND coupon_id=4"));}
    @Test void newUserRequiresRegistrationEvidenceAndOnceAcrossStores(){f.db.update("UPDATE yshop_coupon SET coupon_kind='NEW_USER' WHERE id IN (1,2)");long id=claim(1,1);assertThrows(RuntimeException.class,()->claim(1,2));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_newcomer"));assertEquals("AVAILABLE",marketing.state(row(id)));}
    @Test void historicalMemberDoesNotBecomeNewByChangingShop(){f.db.update("UPDATE yshop_user SET create_time='2020-01-01' WHERE id=1");f.db.update("UPDATE yshop_coupon SET coupon_kind='NEW_USER' WHERE id IN (1,2)");for(long c:List.of(1L,2L))assertThrows(RuntimeException.class,()->claim(1,c));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_newcomer"));}
    @Test void publicCodeCannotBeBypassedWithIdAndIsNeverReturned(){f.db.update("UPDATE yshop_coupon SET claim_mode='CODE',redemption_code_hash=? WHERE id=1",CouponMarketingService.codeHash("SYNTHETIC-CODE"));assertThrows(RuntimeException.class,()->claim(1,1));String k=key();long id=marketing.claim(1,null,"SYNTHETIC-CODE",k);assertEquals(id,marketing.claim(1,null,"SYNTHETIC-CODE",k));assertThrows(RuntimeException.class,()->marketing.claim(2,null,"WRONG-CODE",key()));assertNull(templates.get(1L).getExchangeCode());assertNull(templates.get(1L).getRedemptionCodeHash());assertNull(row(id).get("exchange_code"));}
    @Test void claimAuditFailureRollsBackCountersInstanceAndNewUser(){f.db.update("UPDATE yshop_coupon SET coupon_kind='NEW_USER' WHERE id=1");f.db.execute("ALTER TABLE yshop_coupon_operation ADD CONSTRAINT injected_claim_fault CHECK(kind<>'CLAIM')");assertThrows(RuntimeException.class,()->claim(1,1));assertEquals(0,n("SELECT receive FROM yshop_coupon WHERE id=1"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_user"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_claim"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_newcomer"));}
    @Test void issuedSnapshotDoesNotFollowTemplateEditsOrDisable(){long id=claim(1,1);String old=row(id).toString();var r=edit(1);r.setValue(new BigDecimal("0.20"));r.setShopId("1,2");r.setIsSwitch(0);templates.update(r);assertEquals(old,row(id).toString());String order=f.place(request(id));assertEquals(new BigDecimal("1.13"),f.db.queryForObject("SELECT pay_price FROM yshop_store_order WHERE order_id=?",BigDecimal.class,order));assertEquals("RESERVED",marketing.state(row(id)));}
    CouponUpdateReqVO edit(long id){var r=new CouponUpdateReqVO();r.setId(id);r.setTemplateVersion(1L);r.setShopId("1");r.setTitle("Synthetic edit");r.setIsSwitch(1);r.setType(0);r.setLeast(BigDecimal.ZERO);r.setValue(new BigDecimal("0.10"));r.setStartTime(now.minusDays(1));r.setEndTime(now.plusDays(30));r.setClaimStartTime(now.minusHours(2));r.setClaimEndTime(now.plusDays(10));r.setDistribute(100);r.setLimit(3);r.setCouponKind("REGULAR");r.setClaimMode("PUBLIC");r.setInstructions("Synthetic rules");return r;}
    @Test void issuedTemplateCannotDeleteOrLowerQuotaBelowEvidence(){claim(1,1);assertThrows(RuntimeException.class,()->templates.delete(1L));var r=edit(1);r.setDistribute(0);assertThrows(RuntimeException.class,()->templates.update(r));assertEquals(100,n("SELECT distribute FROM yshop_coupon WHERE id=1"));}
    @Test void adminScopeCannotBeForged(){f.access(101,false);assertThrows(RuntimeException.class,()->templates.get(2L));assertThrows(RuntimeException.class,()->templates.get(3L));var r=edit(1);r.setShopId("0");assertThrows(RuntimeException.class,()->templates.update(r));r.setShopId("1,2");assertThrows(RuntimeException.class,()->templates.update(r));assertEquals(1,templates.getList().size());}
    @Test void headquartersCanReadGlobalAndAllAuthorizedStores(){field(marketing,"access",f.access(999,true));assertEquals(4,templates.getList().size());assertNotNull(templates.get(3L));}
    @Test void rawIssuedCrudCannotGrantOrReactivate(){assertThrows(RuntimeException.class,()->instances.createUser(new co.yixiang.yshop.module.coupon.controller.admin.couponuser.vo.CouponUserCreateReqVO()));assertThrows(RuntimeException.class,()->instances.updateUser(new co.yixiang.yshop.module.coupon.controller.admin.couponuser.vo.CouponUserUpdateReqVO()));long id=claim(1,1);assertThrows(RuntimeException.class,()->instances.deleteUser((int)id));f.access(102,false);assertThrows(RuntimeException.class,()->instances.getUser((int)id));}
    @Test void explicitInvalidationNeedsReasonAndCannotTouchReserved(){long id=claim(1,1);assertThrows(RuntimeException.class,()->marketing.invalidate(id,""));String order=f.place(request(id));assertThrows(RuntimeException.class,()->marketing.invalidate(id,"synthetic revoke"));assertEquals("RESERVED",marketing.state(row(id)));f.orders.cancel(order,1L,false);marketing.invalidate(id,"synthetic revoke");assertEquals("INVALID",marketing.state(row(id)));assertThrows(RuntimeException.class,()->f.place(request(id)));}
    @ParameterizedTest @ValueSource(strings={"owner","shop","type","threshold","expired","future","negative","invalid","usedUnknown"})
    void invalidUseLeavesStockAndCouponUntouched(String why){long id=claim(1,1);switch(why){case "owner"->f.db.update("UPDATE yshop_coupon_user SET user_id=2 WHERE id=?",id);case "shop"->f.db.update("UPDATE yshop_coupon_user SET shop_id='2' WHERE id=?",id);case "type"->f.db.update("UPDATE yshop_coupon_user SET type=2 WHERE id=?",id);case "threshold"->f.db.update("UPDATE yshop_coupon_user SET least=30 WHERE id=?",id);case "expired"->f.db.update("UPDATE yshop_coupon_user SET end_time=? WHERE id=?",now,id);case "future"->f.db.update("UPDATE yshop_coupon_user SET start_time=? WHERE id=?",now.plusMinutes(1),id);case "negative"->f.db.update("UPDATE yshop_coupon_user SET `value`=-1 WHERE id=?",id);case "invalid"->f.db.update("UPDATE yshop_coupon_user SET invalid_reason='synthetic' WHERE id=?",id);default->f.db.update("UPDATE yshop_coupon_user SET status=1 WHERE id=?",id);}String before=row(id).toString();assertThrows(RuntimeException.class,()->f.place(request(id)));assertEquals(before,row(id).toString());assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_store_order"));}
    @Test void expiredReservationReleaseDoesNotRenewEligibility(){long id=claim(1,1);String order=f.place(request(id));f.db.update("UPDATE yshop_coupon_user SET end_time=? WHERE id=?",now,id);assertEquals("RESERVED",marketing.state(row(id)));f.orders.cancel(order,1L,false);f.orders.cancel(order,1L,false);assertEquals("EXPIRED",marketing.state(row(id)));assertNull(row(id).get("reserved_order_id"));assertThrows(RuntimeException.class,()->f.place(request(id)));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_operation WHERE kind='RELEASE'"));}
    @Test void zeroOrderRejectedAndReservationRollsBack(){long id=claim(1,1);f.db.update("UPDATE yshop_coupon_user SET `value`=999 WHERE id=?",id);assertThrows(RuntimeException.class,()->f.place(request(id)));assertEquals("AVAILABLE",marketing.state(row(id)));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_operation WHERE kind='RESERVE'"));}
    @Test void reservationAuditFailureRollsBackInventoryAndSubmission(){long id=claim(1,1);f.db.execute("ALTER TABLE yshop_coupon_operation ADD CONSTRAINT injected_reserve_fault CHECK(kind<>'RESERVE')");assertThrows(RuntimeException.class,()->f.place(request(id)));assertEquals("AVAILABLE",marketing.state(row(id)));assertEquals(0,n("SELECT COUNT(*) FROM yshop_order_submission"));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));}
    @Test void releaseAuditFailureRollsBackBusinessState(){long id=claim(1,1);String order=f.place(request(id));f.db.execute("ALTER TABLE yshop_coupon_operation ADD CONSTRAINT injected_release_fault CHECK(kind<>'RELEASE')");assertThrows(RuntimeException.class,()->f.orders.cancel(order,1L,false));assertEquals("RESERVED",marketing.state(row(id)));assertEquals(9,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(0,n("SELECT deleted FROM yshop_store_order WHERE order_id=?",order));}
    @Test void historicalAmbiguousStatusIsReviewNotUsed(){long id=claim(1,1);f.db.update("UPDATE yshop_coupon_user SET status=1 WHERE id=?",id);assertEquals("REVIEW_REQUIRED",marketing.state(row(id)));assertEquals(1,marketing.statistics(1).get("REVIEW_REQUIRED"));assertEquals(0,marketing.statistics(1).get("USED"));}
    @Test void receiptWithoutSuccessfulPaymentCannotProveRedemption(){long id=claim(1,1);String order=f.place(request(id));f.db.update("INSERT INTO yshop_order_payment(order_id,status) VALUES(?,'RECEIVED')",order);assertEquals("RESERVED",marketing.state(row(id)));}
    @Test void pendingAttemptCancellationDoesNotReleaseOrAudit(){long id=claim(1,1);String order=f.place(request(id));f.db.update("INSERT INTO yshop_order_payment_attempt(attempt_id,order_id,status,provider) VALUES('synthetic-active',?,'CREATED','WECHAT')",order);String before=row(id).toString();assertThrows(RuntimeException.class,()->f.orders.cancel(order,1L,false));assertEquals(before,row(id).toString());assertEquals(9,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_coupon_operation WHERE kind='RELEASE'"));}
    @RepeatedTest(20) void twentyMembersClaimLastCoupon() throws Exception {f.db.update("UPDATE yshop_coupon SET distribute=1 WHERE id=1");var next=new java.util.concurrent.atomic.AtomicInteger(1);var results=f.parallel(20,()->{try{claim(next.getAndIncrement(),1);return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException denied){return false;}});assertEquals(1,Collections.frequency(results,true));assertEquals(1,n("SELECT receive FROM yshop_coupon WHERE id=1"));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_user"));}
    @RepeatedTest(20) void sameMemberTwentyKeysLimitOne() throws Exception {var results=f.parallel(20,()->{try{claim(1,1);return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException denied){return false;}});assertEquals(1,Collections.frequency(results,true));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_user"));}
    @RepeatedTest(20) void sameMemberTwentyKeysLimitThree() throws Exception {var results=f.parallel(20,()->{try{claim(1,4);return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException denied){return false;}});assertEquals(3,Collections.frequency(results,true));assertEquals(3,n("SELECT receive FROM yshop_coupon WHERE id=4"));}
    @RepeatedTest(20) void sameClaimRequestTwentyRetries() throws Exception {String key=key();var results=f.parallel(20,()->{marketing.claim(1,1L,null,key);return true;});assertEquals(20,Collections.frequency(results,true));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_user"));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_operation WHERE kind='CLAIM'"));}
    @RepeatedTest(20) void claimVersusActivityDisable() throws Exception {var next=new java.util.concurrent.atomic.AtomicInteger();var results=f.parallel(20,()->{f.access(103,false);try{if(next.getAndIncrement()==0){var r=edit(1);r.setIsSwitch(0);templates.update(r);}else claim(1,1);return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException denied){return false;}});assertTrue(results.contains(true));assertEquals(n("SELECT COUNT(*) FROM yshop_coupon_user"),n("SELECT receive FROM yshop_coupon WHERE id=1"));assertEquals(0,n("SELECT is_switch FROM yshop_coupon WHERE id=1"));}
    @RepeatedTest(20) void claimVersusQuotaChange() throws Exception {var next=new java.util.concurrent.atomic.AtomicInteger(1);var results=f.parallel(20,()->{f.access(103,false);int uid=next.getAndIncrement();try{if(uid==1){var r=edit(1);r.setDistribute(1);templates.update(r);}else claim(uid,1);return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException denied){return false;}});assertTrue(results.contains(true));assertTrue(n("SELECT receive FROM yshop_coupon WHERE id=1")<=n("SELECT distribute FROM yshop_coupon WHERE id=1"));assertEquals(n("SELECT COUNT(*) FROM yshop_coupon_user"),n("SELECT receive FROM yshop_coupon WHERE id=1"));}
    @RepeatedTest(20) void sameNewcomerTwentyConcurrentClaims() throws Exception {f.db.update("UPDATE yshop_coupon SET coupon_kind='NEW_USER',`limit`=20 WHERE id=1");var results=f.parallel(20,()->{try{claim(1,1);return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException denied){return false;}});assertEquals(1,Collections.frequency(results,true));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_newcomer"));assertEquals(1,n("SELECT receive FROM yshop_coupon WHERE id=1"));}
    @RepeatedTest(20) void publicCodeTwentySameRetries() throws Exception {f.db.update("UPDATE yshop_coupon SET claim_mode='CODE',redemption_code_hash=? WHERE id=1",CouponMarketingService.codeHash("SYNTHETIC-CODE"));String k=key();var results=f.parallel(20,()->{marketing.claim(1,null,"SYNTHETIC-CODE",k);return true;});assertEquals(20,Collections.frequency(results,true));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_user"));}
    @RepeatedTest(20) void sameCouponTwentyOrdersOnlyOneReservation() throws Exception {long id=claim(1,1);var results=f.parallel(20,()->{try{f.place(request(id));return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException denied){return false;}});assertEquals(1,Collections.frequency(results,true));assertEquals(1,n("SELECT COUNT(*) FROM yshop_store_order"));assertEquals(9,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_operation WHERE kind='RESERVE'"));}
    @RepeatedTest(20) void cancellationAndTimeoutReleaseOnce() throws Exception {long id=claim(1,1);String order=f.place(request(id));f.db.update("UPDATE yshop_store_order SET create_time=? WHERE order_id=?",now.minusHours(1),order);var results=f.parallel(20,()->{f.orders.cancel(order,1L,java.util.concurrent.ThreadLocalRandom.current().nextBoolean());return true;});assertEquals(20,Collections.frequency(results,true));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_operation WHERE kind='RELEASE'"));assertEquals("AVAILABLE",marketing.state(row(id)));}
    @Test void permissionAnnotationsProtectAllUserReads() throws Exception {
        var c=co.yixiang.yshop.module.coupon.controller.admin.couponuser.CouponUserController.class;
        for(var method:c.getDeclaredMethods()) if(method.getName().startsWith("getUser")||method.getName().equals("exportUserExcel"))assertNotNull(method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class));
    }
    @Test void legacyAuditIsReadOnlyAndReportsUnknownCounterAndMissingEvidence() {
        long id=claim(1,1);f.db.update("UPDATE yshop_coupon_user SET status=1,reserved_order_id='synthetic-missing' WHERE id=?",id);
        f.db.update("UPDATE yshop_coupon SET receive=99 WHERE id=1");
        field(marketing,"access",f.access(999,true));
        String before=f.db.queryForList("SELECT * FROM yshop_coupon_user").toString()+f.db.queryForList("SELECT * FROM yshop_coupon").toString()+f.db.queryForList("SELECT * FROM yshop_coupon_operation").toString();
        var result=marketing.legacyAudit();assertEquals(1,result.get("REVIEW_REQUIRED"));assertEquals(1,result.get("MISSING_ORDER"));assertEquals(1,result.get("COUNTER_MISMATCH"));assertEquals(0,result.get("USED"));
        assertEquals(before,f.db.queryForList("SELECT * FROM yshop_coupon_user").toString()+f.db.queryForList("SELECT * FROM yshop_coupon").toString()+f.db.queryForList("SELECT * FROM yshop_coupon_operation").toString());
        field(marketing,"access",f.access(101,false));assertThrows(RuntimeException.class,()->marketing.legacyAudit());
    }
    @RepeatedTest(20) void oneNewcomerAcrossConcurrentCampaigns() throws Exception {
        f.db.update("UPDATE yshop_coupon SET coupon_kind='NEW_USER' WHERE id IN (1,2)");var next=new java.util.concurrent.atomic.AtomicInteger();
        var result=f.parallel(20,()->{try{claim(1,next.getAndIncrement()%2+1);return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException rejected){return false;}});
        assertEquals(1,Collections.frequency(result,true));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_newcomer"));assertEquals(1,n("SELECT COUNT(*) FROM yshop_coupon_user"));assertEquals(1,n("SELECT SUM(receive) FROM yshop_coupon"));
    }
    @RepeatedTest(20) void expiryAndOrderReservationRace() throws Exception {
        long id=claim(1,1);var next=new java.util.concurrent.atomic.AtomicInteger();
        var result=f.parallel(20,()->{try{if(next.getAndIncrement()==0)f.db.update("UPDATE yshop_coupon_user SET end_time=? WHERE id=?",now,id);else f.place(request(id));return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException rejected){return false;}});
        assertTrue(result.contains(true));assertTrue(n("SELECT COUNT(*) FROM yshop_store_order")<=1);assertEquals(10-n("SELECT COUNT(*) FROM yshop_store_order"),n("SELECT stock FROM yshop_store_product WHERE id=1"));
        for(String order:f.db.queryForList("SELECT order_id FROM yshop_store_order",String.class))f.orders.cancel(order,1L,false);
        assertEquals("EXPIRED",marketing.state(row(id)));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));
    }
}
