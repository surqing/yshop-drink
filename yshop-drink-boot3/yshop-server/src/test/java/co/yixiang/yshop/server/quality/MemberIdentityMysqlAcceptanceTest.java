package co.yixiang.yshop.server.quality;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.*;
import co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthSmsLoginReqVO;
import co.yixiang.yshop.module.member.controller.app.user.vo.AppUserUpdateMobileReqVO;
import co.yixiang.yshop.module.system.dal.mysql.sms.SmsCodeMapper;
import co.yixiang.yshop.module.system.api.sms.dto.code.SmsCodeUseReqDTO;
import co.yixiang.yshop.framework.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberIdentityMysqlAcceptanceTest extends SecurityMysqlTestSupport {
    // These product assertions catch phone-only grants and incomplete principal eligibility.
    @Test void unregisteredSmsNeverCreatesMemberTokenOrSuccessLog(){
        code("13800000001",1,"123456");
        assertThrows(ServiceException.class,()->auth.smsLogin(login("13800000001",null)));
        assertEquals(0,count("yshop_user"));assertEquals(0,count("system_oauth2_access_token"));
        assertEquals(0,count("system_oauth2_refresh_token"));assertEquals(0,count("system_login_log"));
        assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));assertTrue(redis.keys("*").isEmpty());
    }
    @Test void phoneOnlySmsCannotGrantToken(){
        long uid=member("13800000001",null);code("13800000001",1,"123456");
        assertThrows(ServiceException.class,()->auth.smsLogin(login("13800000001",null)));
        assertNull(members.selectById(uid).getRoutineOpenid());assertEquals(1,count("yshop_user"));
        assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));
        assertEquals(0,count("system_login_log"));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
        assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());
    }
    @Test void phoneOnlyPasswordCannotBypassCompletion(){
        long uid=member("13800000001",null);
        assertThrows(ServiceException.class,()->auth.login(co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthLoginReqVO.builder().mobile("13800000001").password("synthetic-password").build()));
        assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM system_login_log WHERE result=0",Integer.class));
        assertEquals(1,count("system_login_log"));assertEquals(uid,jdbc.queryForObject("SELECT user_id FROM system_login_log",Long.class));
        assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());
    }
    @Test void miniOnlySessionCannotGrantToken() throws Exception {
        long uid=member(null,"synthetic-mini");miniProof("13800000001","synthetic-mini","synthetic-login");
        var result=auth.weixinMiniAppLogin2(co.yixiang.yshop.module.member.controller.app.auth.vo.AppWeixinMiniLoginVO.builder().code("synthetic-login").build());
        assertNull(result.getAccessToken());assertNull(result.getRefreshToken());assertNull(result.getUserInfo());assertEquals("synthetic-mini",result.getOpenId());
        assertNull(phone(uid));assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_login_log"));
        assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());
    }
    void legacyIncompleteCredentials(boolean missingPhone,boolean refreshFirst){
        long uid=member("13800000001","synthetic-mini");var old=tokens.createAccessToken(new co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenCreateReqDTO().setUserId(uid).setUserType(1).setClientId("default"));
        jdbc.update("UPDATE yshop_user SET "+(missingPhone?"mobile":"routine_openid")+"=NULL WHERE id=?",uid);
        if(refreshFirst)assertThrows(ServiceException.class,()->auth.refreshToken(old.getRefreshToken()));
        assertThrows(ServiceException.class,()->tokens.checkAccessToken(old.getAccessToken()));
        if(!refreshFirst)assertThrows(ServiceException.class,()->auth.refreshToken(old.getRefreshToken()));
        assertEquals(1,count("system_oauth2_refresh_token"));assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());
        assertDoesNotThrow(()->tokens.removeAccessToken(old.getAccessToken(),1));assertEquals(0,count("system_oauth2_refresh_token"));
    }
    @Test void legacyPhoneOnlyTokenCannotValidateOrRefresh(){legacyIncompleteCredentials(false,false);}
    @Test void legacyMiniOnlyTokenCannotValidateOrRefresh(){legacyIncompleteCredentials(true,false);}
    @Test void legacyPhoneOnlyRefreshCannotGrantToken(){legacyIncompleteCredentials(false,true);}
    @Test void legacyMiniOnlyRefreshCannotGrantToken(){legacyIncompleteCredentials(true,true);}
    void legacyMalformedProvider(boolean refreshFirst){
        long uid=member("13800000001","synthetic-mini");var old=tokens.createAccessToken(new co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenCreateReqDTO().setUserId(uid).setUserType(1).setClientId("default"));
        jdbc.update("UPDATE yshop_user SET routine_openid=? WHERE id=?","synthetic-mini\n",uid);
        if(refreshFirst)assertThrows(ServiceException.class,()->auth.refreshToken(old.getRefreshToken()));
        else assertThrows(ServiceException.class,()->tokens.checkAccessToken(old.getAccessToken()));
        assertEquals(1,count("system_oauth2_refresh_token"));assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());
    }
    @Test void malformedProviderCannotValidateLegacyToken(){legacyMalformedProvider(false);}
    @Test void malformedProviderCannotRefreshLegacyToken(){legacyMalformedProvider(true);}
    @Test void twentyUnknownSmsRequestsNeverRegisterMembers() throws Exception {
        var pool=Executors.newFixedThreadPool(20);var gate=new CyclicBarrier(20);
        for(int n=0;n<20;n++)code("13800000001",1,String.valueOf(200000+n));
        try {var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<20;n++){String proof=String.valueOf(200000+n);futures.add(pool.submit(()->{requestContext();gate.await(20,TimeUnit.SECONDS);try{auth.smsLogin(AppAuthSmsLoginReqVO.builder().mobile("13800000001").code(proof).build());return true;}catch(ServiceException denied){return false;}}));}
            for(var f:futures)assertFalse(f.get(60,TimeUnit.SECONDS));assertEquals(0,count("yshop_user"));assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_login_log"));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void twentyDistinctUnknownSmsPhonesNeverRegisterMembers() throws Exception {
        var pool=Executors.newFixedThreadPool(20);var gate=new CyclicBarrier(20);
        for(int n=0;n<20;n++)code(String.format("13800000%03d",n),1,"123456");
        try {var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<20;n++){String phone=String.format("13800000%03d",n);futures.add(pool.submit(()->{requestContext();gate.await(20,TimeUnit.SECONDS);try{auth.smsLogin(login(phone,null));return true;}catch(ServiceException denied){return false;}}));}
            for(var f:futures)assertFalse(f.get(60,TimeUnit.SECONDS));assertEquals(0,count("yshop_user"));assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));assertEquals(0,count("system_login_log"));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));assertTrue(redis.keys("*").isEmpty());
        }finally{pool.shutdownNow();}
    }
    @Test void twentyDualProofRegistrationsResolveOneUidWithoutReplayingSdkCodes() throws Exception {
        var wx=mock(cn.binarywang.wx.miniapp.api.WxMaService.class);var provider=mock(cn.binarywang.wx.miniapp.api.WxMaUserService.class);when(wx.getUserService()).thenReturn(provider);set(authTarget,"wxMaService",wx);
        for(int n=0;n<20;n++){var session=new cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult();session.setOpenid("synthetic-shared");when(provider.getSessionInfo("login-"+n)).thenReturn(session);var phone=new cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo();phone.setPurePhoneNumber("13800000001");when(provider.getNewPhoneNoInfo("phone-"+n)).thenReturn(phone);}
        var mapper=spy(members);var barrier=new CyclicBarrier(20);var first=ThreadLocal.withInitial(()->true);var connections=ConcurrentHashMap.<Long>newKeySet();
        doAnswer(c->{var value=c.callRealMethod();if(first.get()){first.set(false);connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));barrier.await(20,TimeUnit.SECONDS);}return value;}).when(mapper).selectByMobile("13800000001");set(authTarget,"userMapper",mapper);
        var pool=Executors.newFixedThreadPool(20);
        try {var futures=new ArrayList<Future<Long>>();for(int n=0;n<20;n++){int worker=n;futures.add(pool.submit(()->{requestContext();var request=co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthWeixinMiniAppLoginReqVO.builder().phoneCode("phone-"+worker).loginCode("login-"+worker).build();return tokens.checkAccessToken(auth.weixinMiniAppLogin(request).getAccessToken()).getUserId();}));}
            var uids=new HashSet<Long>();for(var f:futures)uids.add(f.get(60,TimeUnit.SECONDS));assertEquals(1,uids.size());assertEquals(20,connections.size());assertEquals(1,count("yshop_user"));assertEquals(20,count("system_oauth2_refresh_token"));assertEquals("synthetic-shared",members.selectById(uids.iterator().next()).getRoutineOpenid());
            for(int n=0;n<20;n++){verify(provider,times(1)).getSessionInfo("login-"+n);verify(provider,times(1)).getNewPhoneNoInfo("phone-"+n);}
        }finally{pool.shutdownNow();}
    }
    @Test void completeMemberSmsLoginPreservesUidAndBothIdentities(){
        long uid=member("13800000001","synthetic-mini");code("13800000001",1,"123456");
        var result=auth.smsLogin(login("13800000001","synthetic-foreign"));
        assertEquals(uid,tokens.checkAccessToken(result.getAccessToken()).getUserId());assertEquals(1,count("yshop_user"));
        assertEquals("synthetic-mini",members.selectById(uid).getRoutineOpenid());assertEquals(1,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
        assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());
        assertEquals(uid,tokens.checkAccessToken(auth.refreshToken(result.getRefreshToken()).getAccessToken()).getUserId());
    }
    co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthWeixinMiniAppLoginReqVO miniRequest(){
        return co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthWeixinMiniAppLoginReqVO.builder().phoneCode("synthetic-phone-code").loginCode("synthetic-login").build();
    }
    @Test void freshPhoneAndMiniProofsCreateOneCompleteMember() throws Exception {
        miniProof("13800000001","synthetic-mini","synthetic-login");var result=auth.weixinMiniAppLogin(miniRequest());
        long uid=tokens.checkAccessToken(result.getAccessToken()).getUserId();assertEquals(1,count("yshop_user"));
        assertEquals("13800000001",phone(uid));assertEquals("synthetic-mini",members.selectById(uid).getRoutineOpenid());
        assertEquals(new java.math.BigDecimal("0.00"),members.selectById(uid).getIntegral());assertEquals(new java.math.BigDecimal("0.00"),members.selectById(uid).getNowMoney());
    }
    @Test void freshDualProofCompletesPhoneOnlyMemberWithoutAssetTransfer() throws Exception {
        long uid=member("13800000001",null);miniProof("13800000001","synthetic-mini","synthetic-login");
        var result=auth.weixinMiniAppLogin(miniRequest());assertEquals(uid,tokens.checkAccessToken(result.getAccessToken()).getUserId());
        assertEquals("synthetic-mini",members.selectById(uid).getRoutineOpenid());assertEquals(1,count("yshop_user"));
        assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());
    }
    @Test void adminWithoutCustomerProofCannotCreateMember(){
        var admin=new co.yixiang.yshop.module.member.service.user.UserServiceImpl();set(admin,"userMapper",members);
        var request=new co.yixiang.yshop.module.member.controller.admin.user.vo.UserCreateReqVO();request.setMobile("13800000001");request.setNickname("synthetic");request.setStatus(0);
        assertThrows(ServiceException.class,()->admin.createUser(request));assertEquals(0,count("yshop_user"));assertEquals(0,count("system_oauth2_access_token"));
    }
    @Test void expiredMiniProofCannotRegisterWithOnlyPhoneProof() throws Exception {
        miniProof("13800000001","synthetic-mini","synthetic-login");var wx=(cn.binarywang.wx.miniapp.api.WxMaService)org.springframework.test.util.ReflectionTestUtils.getField(authTarget,"wxMaService");
        when(wx.getUserService().getSessionInfo("synthetic-login")).thenThrow(new me.chanjar.weixin.common.error.WxErrorException(new me.chanjar.weixin.common.error.WxError()));
        assertThrows(ServiceException.class,()->auth.weixinMiniAppLogin(miniRequest()));assertEquals(0,count("yshop_user"));assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_login_log"));
    }
    @Test void expiredPhoneProofCannotRegisterWithOnlyMiniProof() throws Exception {
        miniProof("13800000001","synthetic-mini","synthetic-login");var wx=(cn.binarywang.wx.miniapp.api.WxMaService)org.springframework.test.util.ReflectionTestUtils.getField(authTarget,"wxMaService");
        when(wx.getUserService().getNewPhoneNoInfo("synthetic-phone-code")).thenThrow(new me.chanjar.weixin.common.error.WxErrorException(new me.chanjar.weixin.common.error.WxError()));
        assertThrows(ServiceException.class,()->auth.weixinMiniAppLogin(miniRequest()));assertEquals(0,count("yshop_user"));assertEquals(0,count("system_oauth2_refresh_token"));assertEquals(0,count("system_login_log"));
    }
    @Test void missingMiniProofCannotRegisterWithOnlyPhoneProof() throws Exception {
        miniProof("13800000001","synthetic-mini","synthetic-login");var request=miniRequest();request.setLoginCode("");
        assertThrows(ServiceException.class,()->auth.weixinMiniAppLogin(request));assertEquals(0,count("yshop_user"));assertEquals(0,count("system_oauth2_access_token"));
    }
    void newRegistrationSqlFault(String table) throws Exception {
        miniProof("13800000001","synthetic-mini","synthetic-login");
        jdbc.execute("CREATE TRIGGER quality_fail_dual_registration BEFORE INSERT ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_REGISTRATION_FAULT'");
        try{assertThrows(org.springframework.dao.DataAccessException.class,()->auth.weixinMiniAppLogin(miniRequest()));assertEquals(0,count("yshop_user"));assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));assertEquals(0,count("system_login_log"));assertTrue(redis.keys("*").isEmpty());}
        finally{jdbc.execute("DROP TRIGGER quality_fail_dual_registration");}
    }
    @Test void dualProofTokenSqlFaultRollsBackNewMemberAndCredentials() throws Exception {newRegistrationSqlFault("system_oauth2_access_token");}
    @Test void dualProofSuccessAuditFaultRollsBackNewMemberAndCredentials() throws Exception {newRegistrationSqlFault("system_login_log");}
    @Test void smsLoginRechecksPhoneAfterOwnerLookup() throws Exception {
        long uid=member("13800000001","synthetic-mini");code("13800000001",1,"123456");var selected=new CountDownLatch(1);var changed=new CountDownLatch(1);var mapper=spy(members);
        doAnswer(c->{var row=c.callRealMethod();selected.countDown();assertTrue(changed.await(15,TimeUnit.SECONDS));return row;}).when(mapper).selectByMobile("13800000001");set(authTarget,"userMapper",mapper);var pool=Executors.newSingleThreadExecutor();
        try{var request=pool.submit(()->{requestContext();return assertThrows(ServiceException.class,()->auth.smsLogin(login("13800000001",null)));});assertTrue(selected.await(15,TimeUnit.SECONDS));jdbc.update("UPDATE yshop_user SET mobile='13800000002' WHERE id=?",uid);changed.countDown();request.get(30,TimeUnit.SECONDS);assertEquals("13800000002",phone(uid));assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());}
        finally{changed.countDown();pool.shutdownNow();}
    }
    @Test void differentVerifiedPhonesCannotShareOneMiniIdentity() throws Exception {
        var wx=mock(cn.binarywang.wx.miniapp.api.WxMaService.class);var provider=mock(cn.binarywang.wx.miniapp.api.WxMaUserService.class);when(wx.getUserService()).thenReturn(provider);set(authTarget,"wxMaService",wx);
        for(int n=0;n<2;n++){var session=new cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult();session.setOpenid("synthetic-shared");when(provider.getSessionInfo("login-"+n)).thenReturn(session);var phone=new cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo();phone.setPurePhoneNumber(n==0?"13800000001":"13800000002");when(provider.getNewPhoneNoInfo("phone-"+n)).thenReturn(phone);}
        var mapper=spy(members);var barrier=new CyclicBarrier(2);var first=ThreadLocal.withInitial(()->true);
        doAnswer(c->{var row=c.callRealMethod();if(first.get()){first.set(false);barrier.await(15,TimeUnit.SECONDS);}return row;}).when(mapper).selectByProvider("synthetic-shared",true);set(authTarget,"userMapper",mapper);var pool=Executors.newFixedThreadPool(2);
        try{var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<2;n++){int worker=n;futures.add(pool.submit(()->{requestContext();try{auth.weixinMiniAppLogin(co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthWeixinMiniAppLoginReqVO.builder().phoneCode("phone-"+worker).loginCode("login-"+worker).build());return true;}catch(ServiceException denied){return false;}}));}int accepted=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))accepted++;assertEquals(1,accepted);assertEquals(1,count("yshop_user"));assertEquals(1,count("system_oauth2_refresh_token"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_user WHERE mobile IS NOT NULL AND routine_openid='synthetic-shared'",Integer.class));assertEquals(new java.math.BigDecimal("0.00"),jdbc.queryForObject("SELECT SUM(now_money) FROM yshop_user",java.math.BigDecimal.class));for(int n=0;n<2;n++){verify(provider,times(1)).getSessionInfo("login-"+n);verify(provider,times(1)).getNewPhoneNoInfo("phone-"+n);}}
        finally{pool.shutdownNow();}
    }
    @BeforeEach void setup() throws Exception {setupDatabase();}
    @AfterEach void close(){closeDatabase();}
    AppAuthSmsLoginReqVO login(String phone,String openid){return AppAuthSmsLoginReqVO.builder().mobile(phone).code("123456").from("routine").openid(openid).build();}
    @Test void verifiedSmsPhoneNeverSelectsClientSuppliedForeignOpenid(){
        long a=member("13800000001","synthetic-a"),b=member("13800000002","synthetic-b");code("13800000001",1,"123456");
        var result=auth.smsLogin(login("13800000001","synthetic-b"));
        assertEquals(a,tokens.checkAccessToken(result.getAccessToken()).getUserId());assertNotEquals(b,result.getUserId());
        assertEquals("13800000002",phone(b));assertEquals(1,count("system_oauth2_refresh_token"));
    }
    @Test void legitimateSmsLoginUsesExistingCompleteMember(){
        long original=member("13800000001","synthetic-a");code("13800000001",1,"123456");var result=auth.smsLogin(login("13800000001",null));
        long uid=tokens.checkAccessToken(result.getAccessToken()).getUserId();assertEquals(original,uid);assertEquals("13800000001",phone(uid));assertEquals("synthetic-a",members.selectById(uid).getRoutineOpenid());assertEquals(1,count("yshop_user"));
    }
    @Test void staleFullMemberWriteCannotRestoreAnyIdentity(){
        long uid=member("13800000001","synthetic-old");var stale=members.selectById(uid);
        jdbc.update("UPDATE yshop_user SET mobile='13800000002',routine_openid='synthetic-new',openid='public-new' WHERE id=?",uid);
        stale.setOpenid("public-old");stale.setIntegral(new java.math.BigDecimal("105"));assertEquals(1,members.updateById(stale));
        assertEquals("13800000002",phone(uid));assertEquals("synthetic-new",members.selectById(uid).getRoutineOpenid());assertEquals("public-new",members.selectById(uid).getOpenid());assertEquals(new java.math.BigDecimal("105.00"),members.selectById(uid).getIntegral());
    }
    AppUserUpdateMobileReqVO change(String old,String next){return AppUserUpdateMobileReqVO.builder().oldMobile(old).mobile(next).oldCode("123456").code("234567").build();}
    @Test void phoneChangeRequiresStoredOldPhoneRatherThanAttackerPhone(){
        long uid=member("13800000001",null);code("13800000002",2,"123456");code("13800000003",2,"234567");
        assertThrows(ServiceException.class,()->users.updateUserMobile(uid,change("13800000002","13800000003")));
        assertEquals("13800000001",phone(uid));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
    }
    @Test void occupiedNewPhoneDoesNotConsumeProofOrChangeEitherMember(){
        long a=member("13800000001",null),b=member("13800000002",null);code("13800000001",2,"123456");code("13800000002",2,"234567");
        assertThrows(ServiceException.class,()->users.updateUserMobile(a,change("13800000001","13800000002")));
        assertEquals("13800000001",phone(a));assertEquals("13800000002",phone(b));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
    }
    @Test void oneRealSmsProofHasExactlyOneConcurrentConsumer() throws Exception {
        code("13800000001",1,"123456");var barrier=new CyclicBarrier(2);var mapper=spy(sql.getMapper(SmsCodeMapper.class));
        doAnswer(c->{var result=c.callRealMethod();barrier.await(15,TimeUnit.SECONDS);return result;}).when(mapper).selectLastByMobile("13800000001","123456",1);
        var one=smsService(mapper);var two=smsService(mapper);var pool=Executors.newFixedThreadPool(2);
        try{List<Future<Boolean>> futures=new ArrayList<>();for(var s:List.of(one,two))futures.add(pool.submit(()->{try{s.useSmsCode(new SmsCodeUseReqDTO().setMobile("13800000001").setCode("123456").setScene(1).setUsedIp("127.0.0.1"));return true;}catch(ServiceException denied){return false;}}));
            int successes=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))successes++;assertEquals(1,successes);assertEquals(1,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
        }finally{pool.shutdownNow();}
    }

    @Test void phonePrefixesResolveOneCanonicalMember(){
        long uid=member("13800000001","synthetic-a");code("13800000001",1,"123456");
        assertEquals(uid,tokens.checkAccessToken(assertDoesNotThrow(()->auth.smsLogin(login("+8613800000001",null))).getAccessToken()).getUserId());assertEquals(1,count("yshop_user"));
    }
    @Test void internationalDialPrefixResolvesExistingCompleteMember(){
        long uid=member("13800000001","synthetic-a");code("13800000001",1,"123456");
        var result=auth.smsLogin(login("008613800000001",null));
        assertEquals(uid,tokens.checkAccessToken(result.getAccessToken()).getUserId());assertEquals(1,count("yshop_user"));assertEquals("13800000001",phone(uid));assertEquals("synthetic-a",members.selectById(uid).getRoutineOpenid());assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());assertEquals(1,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
    }
    @Test void twoVerifiedMiniOwnersConflictWithoutAssetOrTokenChanges() throws Exception {
        long a=member("13800000001",null),b=member("13800000002","synthetic-b");miniProof("13800000001","synthetic-b","synthetic-login");cachedSocial("synthetic-b","synthetic-login");
        assertThrows(ServiceException.class,()->auth.weixinMiniAppLogin(co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthWeixinMiniAppLoginReqVO.builder().phoneCode("synthetic-phone-code").loginCode("synthetic-login").build()));
        assertEquals("13800000001",phone(a));assertEquals("13800000002",phone(b));assertEquals(0,count("system_oauth2_refresh_token"));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(b).getIntegral());
    }
    @Test void verifiedSingleSidedMiniIdentityKeepsOriginalUidAndAssets() throws Exception {
        long uid=member(null,"synthetic-b");miniProof("13800000001","synthetic-b","synthetic-login");cachedSocial("synthetic-b","synthetic-login");
        var result=auth.weixinMiniAppLogin(co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthWeixinMiniAppLoginReqVO.builder().phoneCode("synthetic-phone-code").loginCode("synthetic-login").build());
        assertEquals(uid,tokens.checkAccessToken(result.getAccessToken()).getUserId());assertEquals("13800000001",phone(uid));assertEquals(1,count("yshop_user"));assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());
    }
    @Test void adminOrdinaryUpdateCannotChangePhoneOrAssets(){
        long uid=member("13800000001",null);var target=new co.yixiang.yshop.module.member.service.user.UserServiceImpl();set(target,"userMapper",members);set(target,"oauth2Tokens",tokens);var service=proxied(target);
        var request=new co.yixiang.yshop.module.member.controller.admin.user.vo.UserUpdateReqVO();request.setId(uid);request.setMobile("13800000002");request.setIntegral(new java.math.BigDecimal("999"));request.setNickname("changed");service.updateUser(request);
        assertEquals("13800000001",phone(uid));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals("changed",members.selectById(uid).getNickname());
    }
    @Test void legitimatePhoneChangeRevokesCurrentCredentials(){
        long uid=member("13800000001","synthetic-a");code("13800000001",1,"123456");var old=auth.smsLogin(login("13800000001",null));code("13800000001",2,"123456");code("13800000003",2,"234567");
        users.updateUserMobile(uid,change("13800000001","13800000003"));assertEquals("13800000003",phone(uid));assertThrows(ServiceException.class,()->tokens.checkAccessToken(old.getAccessToken()));assertEquals(0,count("system_oauth2_refresh_token"));
    }
    @Test void parallelSmsLoginsAllResolveExistingCompleteMember() throws Exception {
        long original=member("13800000001","synthetic-a");var pool=Executors.newFixedThreadPool(20);var barrier=new CyclicBarrier(20);var mapper=spy(members);var first=ThreadLocal.withInitial(()->true);var connections=ConcurrentHashMap.<Long>newKeySet();
        doAnswer(c->{var value=c.callRealMethod();if(first.get()){first.set(false);connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));barrier.await(20,TimeUnit.SECONDS);}return value;}).when(mapper).selectByMobile("13800000001");
        set(authTarget,"userMapper",mapper);var us=new co.yixiang.yshop.module.member.service.user.MemberUserServiceImpl();set(us,"memberUserMapper",mapper);set(us,"baseMapper",mapper);set(us,"passwordEncoder",encoder);set(authTarget,"userService",proxied(us));
        for(int n=0;n<20;n++)code("13800000001",1,String.valueOf(200000+n));
        try {var futures=new ArrayList<Future<Long>>();for(int n=0;n<20;n++){final String code=String.valueOf(200000+n);futures.add(pool.submit(()->{requestContext();return tokens.checkAccessToken(auth.smsLogin(AppAuthSmsLoginReqVO.builder().mobile("13800000001").code(code).build()).getAccessToken()).getUserId();}));}
            var uids=new HashSet<Long>();for(var f:futures)uids.add(assertDoesNotThrow(()->f.get(60,TimeUnit.SECONDS)));assertEquals(Set.of(original),uids);assertEquals(1,count("yshop_user"));assertEquals(20,count("system_oauth2_refresh_token"));assertEquals(20,connections.size());
        }finally{pool.shutdownNow();}
    }

    @Test void genericMemberBindingCannotTransferAnotherMembersIdentity() throws Exception {
        long a=member("13800000001","synthetic-a"),b=member("13800000002","synthetic-b"),social=cachedSocial("synthetic-b","synthetic-login");jdbc.update("INSERT INTO system_social_user_bind(user_id,user_type,social_type,social_user_id) VALUES(?,1,34,?)",b,social);
        miniProof("13800000001","synthetic-b","synthetic-login");code("13800000001",5,"345678");code("13800000001",1,"123456");var req=login("13800000001",null);req.setSocialType(34);req.setSocialCode("synthetic-login");req.setSocialState("");
        assertEquals(a,tokens.checkAccessToken(auth.smsLogin(req).getAccessToken()).getUserId());assertEquals(b,jdbc.queryForObject("SELECT user_id FROM system_social_user_bind WHERE deleted=0",Long.class));assertEquals("synthetic-a",members.selectById(a).getRoutineOpenid());assertEquals("synthetic-b",members.selectById(b).getRoutineOpenid());
    }
    @Test void legitimateBindingUsesFreshProofAndCanonicalMemberColumn() throws Exception {
        long uid=member("13800000001","synthetic-a");code("13800000001",1,"123456");var old=auth.smsLogin(login("13800000001",null));miniProof("13800000001","synthetic-a","synthetic-login");cachedSocial("synthetic-a","synthetic-login");code("13800000001",5,"345678");
        var controller=socialController(uid);var req=bindRequest("synthetic-login","345678");assertDoesNotThrow(()->controller.socialBind(req));assertEquals("synthetic-a",members.selectById(uid).getRoutineOpenid());assertThrows(ServiceException.class,()->tokens.checkAccessToken(old.getAccessToken()));assertEquals(0,count("system_oauth2_refresh_token"));
    }
    @Test void passwordLoginCannotTransferOptionalGenericSocialBinding(){
        long a=member("13800000001","synthetic-a"),b=member("13800000002","synthetic-b"),social=cachedSocial("synthetic-b","synthetic-login");jdbc.update("INSERT INTO system_social_user_bind(user_id,user_type,social_type,social_user_id) VALUES(?,1,34,?)",b,social);
        var req=co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthLoginReqVO.builder().mobile("13800000001").password("synthetic-password").socialType(34).socialCode("synthetic-login").socialState("").build();
        assertEquals(a,tokens.checkAccessToken(auth.login(req).getAccessToken()).getUserId());assertEquals(b,jdbc.queryForObject("SELECT user_id FROM system_social_user_bind WHERE deleted=0",Long.class));
    }
    void passwordFailureAudit(String phone,String password,int error,int result,Long uid){
        var request=co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthLoginReqVO.builder().mobile(phone).password(password).build();
        assertEquals(error,assertThrows(ServiceException.class,()->auth.login(request)).getCode());
        assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM system_login_log WHERE result=0",Integer.class));
        assertEquals(1,count("system_login_log"));
        assertEquals(result,jdbc.queryForObject("SELECT result FROM system_login_log",Integer.class));
        assertEquals(phone,jdbc.queryForObject("SELECT username FROM system_login_log",String.class));
        // Canonical schema represents an unknown user by NOT NULL DEFAULT 0.
        assertEquals(uid==null?0L:uid,jdbc.queryForObject("SELECT user_id FROM system_login_log",Long.class));
    }
    @Test void nonexistentPasswordAccountPersistsRejectedAuthenticationAudit(){
        passwordFailureAudit("13800000001","synthetic-password",co.yixiang.yshop.module.member.enums.ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS.getCode(),10,null);
        assertEquals(0,count("yshop_user"));
    }
    @Test void wrongPasswordPersistsRejectedAuthenticationAudit(){
        long uid=member("13800000001",null);
        passwordFailureAudit("13800000001","synthetic-wrong",co.yixiang.yshop.module.member.enums.ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS.getCode(),10,uid);
        assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());
    }
    @Test void disabledPasswordAccountPersistsRejectedAuthenticationAudit(){
        long uid=member("13800000001",null);jdbc.update("UPDATE yshop_user SET status=1 WHERE id=?",uid);
        passwordFailureAudit("13800000001","synthetic-password",co.yixiang.yshop.module.member.enums.ErrorCodeConstants.AUTH_LOGIN_USER_DISABLED.getCode(),20,uid);
        assertEquals(1,members.selectById(uid).getStatus());
    }
    @Test void legitimatePasswordLoginCommitsTokenAndOnlySuccessAudit(){
        long uid=member("13800000001","synthetic-a");
        var result=auth.login(co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthLoginReqVO.builder().mobile("13800000001").password("synthetic-password").build());
        assertEquals(uid,tokens.checkAccessToken(result.getAccessToken()).getUserId());
        assertEquals(1,count("system_oauth2_access_token"));assertEquals(1,count("system_oauth2_refresh_token"));
        assertEquals(1,count("system_login_log"));assertEquals(0,jdbc.queryForObject("SELECT result FROM system_login_log",Integer.class));
        assertEquals(uid,jdbc.queryForObject("SELECT user_id FROM system_login_log",Long.class));
    }
    @Test void identityUniqueIndexesAlsoReserveDeletedMemberValues(){
        long uid=member("13800000001","synthetic-a");jdbc.update("UPDATE yshop_user SET openid='public-a',deleted=1 WHERE id=?",uid);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->member("13800000001",null));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->member("13800000002","synthetic-a"));
        long other=member("13800000003",null);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE yshop_user SET openid='public-a' WHERE id=?",other));
    }

    @Test void receiptBillFailureRollsBackOrderStatusPointsAndLog(){
        long uid=member("13800000001",null),oid=order(uid);var mapper=spy(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class));
        doThrow(new IllegalStateException("synthetic-bill-failure")).when(mapper).insert(any(co.yixiang.yshop.module.member.dal.dataobject.userbill.UserBillDO.class));
        var service=receipt(sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class),bills(mapper));
        assertThrows(IllegalStateException.class,()->service.takeOrder("synthetic-receipt",uid));assertEquals(1,jdbc.queryForObject("SELECT status FROM yshop_store_order WHERE id=?",Integer.class,oid));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_store_order_status WHERE oid=?",Integer.class,oid));
    }
    @Test void twoConcurrentReceiptsGrantPointsAndBillExactlyOnce() throws Exception {
        long uid=member("13800000001",null),oid=order(uid);var mapper=spy(sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class));var barrier=new CyclicBarrier(2);
        // Existing unlocked SELECTs must overlap. Future Green may lock before this read;
        // a bounded barrier timeout then permits serialized execution instead of deadlocking the fixture.
        doAnswer(c->{var row=c.callRealMethod();try{barrier.await(2,TimeUnit.SECONDS);}catch(TimeoutException|BrokenBarrierException serial){}return row;}).when(mapper).selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var service=receipt(mapper,bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class)));var pool=Executors.newFixedThreadPool(2);
        try{var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<2;n++)futures.add(pool.submit(()->{try{service.takeOrder("synthetic-receipt",uid);return true;}catch(ServiceException rejected){return false;}}));int ok=0;for(var f:futures)if(assertDoesNotThrow(()->f.get(30,TimeUnit.SECONDS)))ok++;assertEquals(1,ok);assertEquals(new java.math.BigDecimal("105.00"),members.selectById(uid).getIntegral());assertEquals(1,count("yshop_user_bill"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_store_order_status WHERE oid=?",Integer.class,oid));}finally{pool.shutdownNow();}
    }

    @Test void memberSmsSenderCannotIssueAdminSceneCodes(){
        assertThrows(ServiceException.class,()->auth.sendSmsCode(null,new co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthSmsSendReqVO().setMobile("13800000001").setScene(21)));assertEquals(0,count("system_sms_code"));
    }
    @Test void bindingCodeSendRequiresAuthenticatedMember(){
        assertThrows(ServiceException.class,()->auth.sendSmsCode(null,new co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthSmsSendReqVO().setMobile("13800000001").setScene(5)));assertEquals(0,count("system_sms_code"));
    }
    @Test void bindingCodeRecipientComesFromStoredCurrentPhone(){
        long uid=member("13800000001",null);auth.sendSmsCode(uid,new co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthSmsSendReqVO().setMobile("13800000002").setScene(5));
        assertEquals("13800000001",jdbc.queryForObject("SELECT mobile FROM system_sms_code",String.class));assertEquals(5,jdbc.queryForObject("SELECT scene FROM system_sms_code",Integer.class));
    }
    @Test void sessionProofCannotLoginAfterIdentityChangedBetweenLookupAndLock() throws Exception {
        long uid=member("13800000001","synthetic-old");miniProof("13800000001","synthetic-old","synthetic-login");var selected=new CountDownLatch(1);var changed=new CountDownLatch(1);var mapper=spy(members);
        doAnswer(c->{var result=c.callRealMethod();selected.countDown();assertTrue(changed.await(15,TimeUnit.SECONDS));return result;}).when(mapper).selectByProvider("synthetic-old",true);set(authTarget,"userMapper",mapper);var pool=Executors.newSingleThreadExecutor();
        try{var task=pool.submit(()->{requestContext();assertThrows(ServiceException.class,()->auth.weixinMiniAppLogin2(co.yixiang.yshop.module.member.controller.app.auth.vo.AppWeixinMiniLoginVO.builder().code("synthetic-login").build()));});assertTrue(selected.await(15,TimeUnit.SECONDS));jdbc.update("UPDATE yshop_user SET routine_openid='synthetic-new' WHERE id=?",uid);changed.countDown();assertDoesNotThrow(()->task.get(30,TimeUnit.SECONDS));assertEquals(0,count("system_oauth2_refresh_token"));assertEquals("synthetic-new",members.selectById(uid).getRoutineOpenid());}finally{changed.countDown();pool.shutdownNow();}
    }
    @Test void passwordProofCannotLoginAfterPhoneChangedBetweenLookupAndGrant() throws Exception {
        long uid=member("13800000001",null);var selected=new CountDownLatch(1);var changed=new CountDownLatch(1);var service=spy(members);
        doAnswer(c->{var result=c.callRealMethod();selected.countDown();assertTrue(changed.await(15,TimeUnit.SECONDS));return result;}).when(service).selectByMobile("13800000001");set(users,"memberUserMapper",service);var pool=Executors.newSingleThreadExecutor();
        try{var task=pool.submit(()->{requestContext();assertThrows(ServiceException.class,()->auth.login(co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthLoginReqVO.builder().mobile("13800000001").password("synthetic-password").build()));});assertTrue(selected.await(15,TimeUnit.SECONDS));jdbc.update("UPDATE yshop_user SET mobile='13800000002' WHERE id=?",uid);changed.countDown();assertDoesNotThrow(()->task.get(30,TimeUnit.SECONDS));assertEquals(0,count("system_oauth2_refresh_token"));assertEquals("13800000002",phone(uid));}finally{changed.countDown();pool.shutdownNow();}
    }
    @Test void exactBillZeroInsertMustFailRatherThanReportSuccess(){
        long uid=member("13800000001",null);var mapper=spy(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class));doReturn(0).when(mapper).insert(any(co.yixiang.yshop.module.member.dal.dataobject.userbill.UserBillDO.class));
        assertThrows(IllegalStateException.class,()->bills(mapper).incomeExact(uid,"synthetic","integral","synthetic",java.math.BigDecimal.ONE,new java.math.BigDecimal("101"),"synthetic","synthetic"));assertEquals(0,count("yshop_user_bill"));
    }
    @Test void concurrentAdminAdjustmentAndReceiptPreserveBothPointDeltas() throws Exception {
        long uid=member("13800000001",null);order(uid);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var mapper=spy(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.wallet.WalletMapper.class));
        doAnswer(c->{locked.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));return c.callRealMethod();}).when(mapper).lockUser(uid);
        var wallet=proxied(new co.yixiang.yshop.module.member.service.wallet.WalletService(mapper));var bill=bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class));var admin=new co.yixiang.yshop.module.member.service.user.UserServiceImpl();set(admin,"userMapper",members);set(admin,"wallets",wallet);set(admin,"userBillService",bill);var service=proxied(admin);
        var req=new co.yixiang.yshop.module.member.controller.admin.user.vo.UserUpdateMoneyReqVO();req.setId(uid);req.setPtype(1);req.setItype(1);req.setMoney("1");req.setIntegral("1");req.setIdempotencyKey("synthetic-adjustment");var receipt=receipt(sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class),bill);var pool=Executors.newFixedThreadPool(2);
        try{var adjustment=pool.submit(()->service.updateMony(req));assertTrue(locked.await(15,TimeUnit.SECONDS));var reward=pool.submit(()->receipt.takeOrder("synthetic-receipt",uid));try{reward.get(2,TimeUnit.SECONDS);}catch(TimeoutException serialization){}release.countDown();adjustment.get(30,TimeUnit.SECONDS);reward.get(30,TimeUnit.SECONDS);assertEquals(new java.math.BigDecimal("106.00"),members.selectById(uid).getIntegral());assertEquals(new java.math.BigDecimal("51.00"),members.selectById(uid).getNowMoney());assertEquals(3,count("yshop_user_bill"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_member_wallet_transaction",Integer.class));}finally{release.countDown();pool.shutdownNow();}
    }

    @Test void migrationRejectsWhitespaceIdentityBeforeAnyMutation(){
        long uid=member("13800000001",null);jdbc.update("UPDATE yshop_user SET openid=' ' WHERE id=?",uid);
        assertThrows(org.springframework.dao.DataAccessException.class,()->migration());assertEquals(" ",members.selectById(uid).getOpenid());assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());
    }
    @Test void migrationRejectsCanonicalPhoneCollisionIncludingDeletedMember(){
        jdbc.execute("ALTER TABLE yshop_user DROP INDEX uk_member_mobile,MODIFY mobile CHAR(15) DEFAULT NULL");long a=member("13800000001",null),b=member("+8613800000001",null);jdbc.update("UPDATE yshop_user SET deleted=1 WHERE id=?",b);
        assertThrows(org.springframework.dao.DataAccessException.class,()->migration());assertEquals("13800000001",phone(a));assertEquals("+8613800000001",phone(b));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_user",Integer.class));
    }
    @Test void caseSensitiveProviderIdsAndMultipleNullsRemainIndependent(){
        long a=member(null,"CaseSensitive"),b=member(null,"casesensitive"),c=member(null,null);assertNotEquals(a,b);assertNotEquals(b,c);assertEquals(3,count("yshop_user"));assertNull(phone(a));assertEquals("CaseSensitive",members.selectById(a).getRoutineOpenid());
    }

    @Test void migrationRejectsPartialValueIndexBeforeMutation(){
        jdbc.execute("ALTER TABLE yshop_user DROP INDEX uk_member_mobile,ADD UNIQUE KEY uk_member_mobile(mobile(3))");long uid=member("13800000001",null);
        assertThrows(org.springframework.dao.DataAccessException.class,()->migration());assertEquals("13800000001",phone(uid));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());
    }
    @Test void dualProofCreationUsesCanonicalPhoneAndIndependentUsername() throws Exception {
        var admin=new co.yixiang.yshop.module.member.service.user.UserServiceImpl();set(admin,"userMapper",members);var request=new co.yixiang.yshop.module.member.controller.admin.user.vo.UserCreateReqVO();request.setMobile("+8613800000001");request.setUsername("13800000001");request.setNickname("synthetic");request.setStatus(0);
        miniProof("+8613800000001","synthetic-a","synthetic-login");long uid=assertDoesNotThrow(()->tokens.checkAccessToken(auth.weixinMiniAppLogin(miniRequest()).getAccessToken()).getUserId());assertEquals("13800000001",phone(uid));assertNotEquals("13800000001",members.selectById(uid).getUsername());
    }

    @Test void startupGuardAcceptsActualMigrationAndRejectsMissingOrPrefixIndexes(){
        var guard=new co.yixiang.yshop.module.member.service.auth.MemberIdentitySchemaGuard(jdbc,false,"synthetic-mini","synthetic-mp");assertDoesNotThrow(guard::verify);
        jdbc.execute("ALTER TABLE yshop_user DROP INDEX uk_member_mobile,ADD UNIQUE KEY uk_member_mobile(mobile(3))");assertThrows(IllegalStateException.class,guard::verify);
        jdbc.execute("ALTER TABLE yshop_user DROP INDEX uk_member_mobile");assertThrows(IllegalStateException.class,guard::verify);
    }
    @Test void startupGuardRejectsTenantOrGenericMemberRemnantsWithoutMutation(){
        long uid=member("13800000001",null);var guard=new co.yixiang.yshop.module.member.service.auth.MemberIdentitySchemaGuard(jdbc,false,"synthetic-mini","synthetic-mp");
        jdbc.update("INSERT INTO system_social_user_bind(user_id,user_type,social_type,social_user_id) VALUES(?,1,34,999)",uid);assertThrows(IllegalStateException.class,guard::verify);assertEquals("13800000001",phone(uid));
        assertThrows(IllegalStateException.class,()->new co.yixiang.yshop.module.member.service.auth.MemberIdentitySchemaGuard(jdbc,true,"synthetic-mini","synthetic-mp").verify());
    }

    void deniedReceipt(String column,int value){
        long uid=member("13800000001",null),oid=order(uid);jdbc.update("UPDATE yshop_store_order SET "+column+"=? WHERE id=?",value,oid);var service=receipt(sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class),bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class)));
        assertThrows(ServiceException.class,()->service.takeOrder("synthetic-receipt",uid));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals(0,count("yshop_user_bill"));assertEquals(1,jdbc.queryForObject("SELECT status FROM yshop_store_order WHERE id=?",Integer.class,oid));
    }
    @Test void refundedOrderCannotGrantReceiptReward(){deniedReceipt("refund_status",1);}
    @Test void systemHiddenOrderCannotGrantReceiptReward(){deniedReceipt("is_system_del",1);}
    @Test void receiptRequiresMemberUidAndRejectsForeignOrder(){
        long uid=member("13800000001",null),other=member("13800000002",null);order(uid);var service=receipt(sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class),bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class)));
        assertThrows(ServiceException.class,()->service.takeOrder("synthetic-receipt",other));assertThrows(ServiceException.class,()->service.takeOrder("synthetic-receipt",null));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals(0,count("yshop_user_bill"));
    }
    @Test void receiptZeroOrderUpdateCannotCommitRewardOrLog(){
        long uid=member("13800000001",null),oid=order(uid);var type=co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class;var mapper=zeroMapper(type,sql.getMapper(type),"updateById","markReceived");var service=receipt(mapper,bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class)));
        assertThrows(ServiceException.class,()->service.takeOrder("synthetic-receipt",uid));assertEquals(1,jdbc.queryForObject("SELECT status FROM yshop_store_order WHERE id=?",Integer.class,oid));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals(0,count("yshop_user_bill"));
    }
    @Test void receiptRealSqlBillFaultRollsBackAllBusinessRows(){
        long uid=member("13800000001",null),oid=order(uid);jdbc.execute("CREATE TRIGGER quality_fail_receipt_bill BEFORE INSERT ON yshop_user_bill FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_BILL_SQL_FAULT'");
        try{var service=receipt(sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class),bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class)));assertThrows(org.springframework.dao.DataAccessException.class,()->service.takeOrder("synthetic-receipt",uid));assertEquals(1,jdbc.queryForObject("SELECT status FROM yshop_store_order WHERE id=?",Integer.class,oid));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals(0,count("yshop_user_bill"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_store_order_status WHERE oid=?",Integer.class,oid));}finally{jdbc.execute("DROP TRIGGER quality_fail_receipt_bill");}
    }
    @Test void statusAnalyticsWaitForCommitAndNeverRunOnRollback(){
        long uid=member("13800000001",null),oid=order(uid);var target=new co.yixiang.yshop.module.order.service.storeorderstatus.StoreOrderStatusServiceImpl();var mapper=sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorderstatus.StoreOrderStatusMapper.class);set(target,"baseMapper",mapper);set(target,"storeOrderStatusMapper",mapper);var analytics=mock(co.yixiang.yshop.module.order.service.storeorder.AsyncStoreOrderService.class);set(target,"asyncStoreOrderService",analytics);var service=proxied(target);var tx=new org.springframework.transaction.support.TransactionTemplate(tm);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{service.create(uid,oid,"synthetic-rollback","synthetic");verifyNoInteractions(analytics);throw new IllegalStateException("synthetic-rollback");}));verifyNoInteractions(analytics);
        tx.executeWithoutResult(s->{service.create(uid,oid,"synthetic-commit","synthetic");verifyNoInteractions(analytics);});verify(analytics,times(1)).orderData(uid);
    }

    @Test void clampedAdminPointDebitRecordsActualDelta(){
        long uid=member("13800000001",null);var bill=bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class));var admin=new co.yixiang.yshop.module.member.service.user.UserServiceImpl();set(admin,"userMapper",members);set(admin,"wallets",proxied(new co.yixiang.yshop.module.member.service.wallet.WalletService(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.wallet.WalletMapper.class))));set(admin,"userBillService",bill);var req=new co.yixiang.yshop.module.member.controller.admin.user.vo.UserUpdateMoneyReqVO();req.setId(uid);req.setPtype(1);req.setItype(2);req.setMoney("1");req.setIntegral("150");req.setIdempotencyKey("synthetic-clamped");proxied(admin).updateMony(req);
        assertEquals(new java.math.BigDecimal("0.00"),members.selectById(uid).getIntegral());assertEquals(new java.math.BigDecimal("100.00"),jdbc.queryForObject("SELECT number FROM yshop_user_bill WHERE category=?",java.math.BigDecimal.class,co.yixiang.yshop.module.member.enums.BillDetailEnum.CATEGORY_2.getValue()));
    }

    @Test void exactExpenditureZeroInsertCannotReportSuccess(){
        long uid=member("13800000001",null);var mapper=spy(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class));doReturn(0).when(mapper).insert(any(co.yixiang.yshop.module.member.dal.dataobject.userbill.UserBillDO.class));
        assertThrows(IllegalStateException.class,()->bills(mapper).expendExact(uid,"synthetic","integral","synthetic",java.math.BigDecimal.ONE,new java.math.BigDecimal("99"),"synthetic","synthetic"));assertEquals(0,count("yshop_user_bill"));
    }
    @Test void receiptZeroStatusInsertRollsBackOrderAndReward(){
        long uid=member("13800000001",null),oid=order(uid);var mapper=zeroMapper(co.yixiang.yshop.module.order.dal.mysql.storeorderstatus.StoreOrderStatusMapper.class,sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorderstatus.StoreOrderStatusMapper.class),"insert");
        var target=new co.yixiang.yshop.module.order.service.storeorderstatus.StoreOrderStatusServiceImpl();set(target,"baseMapper",mapper);set(target,"storeOrderStatusMapper",mapper);set(target,"asyncStoreOrderService",mock(co.yixiang.yshop.module.order.service.storeorder.AsyncStoreOrderService.class));
        var service=receipt(sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class),bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class)));set(service,"storeOrderStatusService",proxied(target));
        assertThrows(IllegalStateException.class,()->service.takeOrder("synthetic-receipt",uid));assertEquals(1,jdbc.queryForObject("SELECT status FROM yshop_store_order WHERE id=?",Integer.class,oid));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());assertEquals(0,count("yshop_user_bill"));
    }
    @Test void legitimatePaidReceiptRetainsExistingStatusEligibility(){
        long uid=member("13800000001",null);for(int status=0;status<=2;status++){
            jdbc.update("DELETE FROM yshop_store_order");long oid=order(uid);jdbc.update("UPDATE yshop_store_order SET status=? WHERE id=?",status,oid);
            var service=receipt(sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class),bills(sql.getMapper(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class)));
            assertDoesNotThrow(()->service.takeOrder("synthetic-receipt",uid));assertEquals(3,jdbc.queryForObject("SELECT status FROM yshop_store_order WHERE id=?",Integer.class,oid));
        }assertEquals(new java.math.BigDecimal("115.00"),members.selectById(uid).getIntegral());assertEquals(new java.math.BigDecimal("50.00"),members.selectById(uid).getNowMoney());assertEquals(3,count("yshop_user_bill"));
    }

    @Test void bindingCannotClaimAnotherMembersVerifiedProviderIdentity() throws Exception {
        long a=member("13800000001",null),b=member("13800000002","synthetic-b");miniProof("13800000001","synthetic-b","synthetic-login");code("13800000001",5,"345678");
        assertThrows(ServiceException.class,()->auth.changeSocialIdentity(a,34,"synthetic-login","345678",false));
        assertNull(members.selectById(a).getRoutineOpenid());assertEquals("synthetic-b",members.selectById(b).getRoutineOpenid());assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));assertEquals(new java.math.BigDecimal("50.00"),members.selectById(b).getNowMoney());
    }
    @Test void bindingDoesNotConsumeLoginSceneProof() throws Exception {
        long uid=member("13800000001",null);miniProof("13800000001","synthetic-a","synthetic-login");code("13800000001",1,"345678");
        assertThrows(ServiceException.class,()->auth.changeSocialIdentity(uid,34,"synthetic-login","345678",false));assertNull(members.selectById(uid).getRoutineOpenid());assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
    }
    @Test void freshUnbindingClearsOnlyOwnIdentityAndRevokesItsCredentials() throws Exception {
        long uid=member("13800000001","synthetic-a"),other=member("13800000002","synthetic-b");code("13800000001",1,"123456");var old=auth.smsLogin(login("13800000001",null));miniProof("13800000001","synthetic-a","synthetic-login");code("13800000001",6,"345678");
        auth.changeSocialIdentity(uid,34,"synthetic-login","345678",true);assertNull(members.selectById(uid).getRoutineOpenid());assertEquals("synthetic-b",members.selectById(other).getRoutineOpenid());assertEquals("13800000001",phone(uid));assertThrows(ServiceException.class,()->tokens.checkAccessToken(old.getAccessToken()));assertThrows(ServiceException.class,()->auth.refreshToken(old.getRefreshToken()));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(uid).getIntegral());
    }
    @Test void unbindingRejectsFreshProofForDifferentIdentity() throws Exception {
        long uid=member("13800000001","synthetic-a");miniProof("13800000001","synthetic-b","synthetic-login");code("13800000001",6,"345678");
        assertThrows(ServiceException.class,()->auth.changeSocialIdentity(uid,34,"synthetic-login","345678",true));assertEquals("synthetic-a",members.selectById(uid).getRoutineOpenid());assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));
    }
    @Test void competingBindingsHaveOneOwnerAndNoAssetTransfer() throws Exception {
        long a=member("13800000001",null),b=member("13800000002",null);miniProof("13800000001","synthetic-shared","synthetic-login");code("13800000001",5,"345678");code("13800000002",5,"345678");var pool=Executors.newFixedThreadPool(2);var gate=new CyclicBarrier(2);
        try {var futures=new ArrayList<Future<Boolean>>();for(long uid:List.of(a,b))futures.add(pool.submit(()->{requestContext();gate.await(15,TimeUnit.SECONDS);try{auth.changeSocialIdentity(uid,34,"synthetic-login","345678",false);return true;}catch(ServiceException denied){return false;}}));int successes=0;for(var f:futures)if(assertDoesNotThrow(()->f.get(30,TimeUnit.SECONDS)))successes++;assertEquals(1,successes);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_user WHERE routine_openid='synthetic-shared'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));assertEquals(new java.math.BigDecimal("100.00"),members.selectById(a).getIntegral());assertEquals(new java.math.BigDecimal("50.00"),members.selectById(b).getNowMoney());}finally{pool.shutdownNow();}
    }
    @Test void tokenSqlFaultRollsBackSmsMemberFamilyAndSuccessLog(){
        long original=member("13800000001","synthetic-a");code("13800000001",1,"123456");jdbc.execute("CREATE TRIGGER quality_fail_identity_token BEFORE INSERT ON system_oauth2_access_token FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_TOKEN_FAULT'");
        try{assertThrows(org.springframework.dao.DataAccessException.class,()->auth.smsLogin(login("13800000001",null)));assertEquals(1,count("yshop_user"));assertEquals("synthetic-a",members.selectById(original).getRoutineOpenid());assertEquals(new java.math.BigDecimal("50.00"),members.selectById(original).getNowMoney());assertEquals(0,count("system_oauth2_refresh_token"));assertEquals(0,count("system_login_log"));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));}finally{jdbc.execute("DROP TRIGGER quality_fail_identity_token");}
        var response=auth.smsLogin(login("13800000001",null));assertEquals("13800000001",phone(tokens.checkAccessToken(response.getAccessToken()).getUserId()));
    }
    @Test void successLogSqlFaultDoesNotPublishCredentialsOrConsumeSms(){
        long original=member("13800000001","synthetic-a");code("13800000001",1,"123456");jdbc.execute("CREATE TRIGGER quality_fail_identity_log BEFORE INSERT ON system_login_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_LOG_FAULT'");
        try{assertThrows(org.springframework.dao.DataAccessException.class,()->auth.smsLogin(login("13800000001",null)));assertEquals(1,count("yshop_user"));assertEquals("synthetic-a",members.selectById(original).getRoutineOpenid());assertEquals(new java.math.BigDecimal("50.00"),members.selectById(original).getNowMoney());assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));assertTrue(redis.keys("*").isEmpty());}finally{jdbc.execute("DROP TRIGGER quality_fail_identity_log");}
    }
    @Test void phoneRevocationSqlFaultRollsBackIdentityAndBothProofs(){
        long uid=member("13800000001","synthetic-a");code("13800000001",1,"123456");var old=auth.smsLogin(login("13800000001",null));code("13800000001",2,"123456");code("13800000003",2,"234567");jdbc.execute("CREATE TRIGGER quality_fail_identity_revoke BEFORE UPDATE ON system_oauth2_refresh_token FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_REVOKE_FAULT'");
        try{assertThrows(org.springframework.dao.DataAccessException.class,()->users.updateUserMobile(uid,change("13800000001","13800000003")));assertEquals("13800000001",phone(uid));assertEquals(0,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code WHERE scene=2",Integer.class));assertEquals(uid,tokens.checkAccessToken(old.getAccessToken()).getUserId());}finally{jdbc.execute("DROP TRIGGER quality_fail_identity_revoke");}
    }
    @Test void twentyRoundsOfTwentySmsLoginsRetainExistingCompleteUids() throws Exception {
        var pool=Executors.newFixedThreadPool(20);
        try{for(int round=0;round<20;round++){
            String phone=String.format("1380000%04d",round+100);long original=member(phone,"synthetic-round-"+round);var gate=new CyclicBarrier(20);var futures=new ArrayList<Future<Long>>();
            for(int worker=0;worker<20;worker++){String proof=String.valueOf(300000+worker);code(phone,1,proof);futures.add(pool.submit(()->{requestContext();gate.await(20,TimeUnit.SECONDS);return tokens.checkAccessToken(auth.smsLogin(AppAuthSmsLoginReqVO.builder().mobile(phone).code(proof).build()).getAccessToken()).getUserId();}));}
            var uids=new HashSet<Long>();for(var f:futures)uids.add(assertDoesNotThrow(()->f.get(60,TimeUnit.SECONDS)));assertEquals(Set.of(original),uids);assertEquals(round+1,count("yshop_user"));assertEquals(20*(round+1),count("system_oauth2_refresh_token"));
        }assertEquals(400,jdbc.queryForObject("SELECT SUM(used) FROM system_sms_code",Integer.class));assertEquals(0,count("yshop_user_bill"));}finally{pool.shutdownNow();}
    }
    @Test void migrationRejectsDuplicateProviderBeforeChangingAnyMember() throws Exception {
        jdbc.execute("ALTER TABLE yshop_user DROP INDEX uk_member_routine_openid");long a=member("13800000001","synthetic-duplicate"),b=member("13800000002","synthetic-duplicate");jdbc.update("UPDATE yshop_user SET deleted=1 WHERE id=?",b);
        assertThrows(org.springframework.dao.DataAccessException.class,()->migration());assertEquals("13800000001",phone(a));assertEquals("13800000002",phone(b));assertEquals(new java.math.BigDecimal("50.00"),members.selectById(a).getNowMoney());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_user",Integer.class));
    }
    @Test void migrationKeepsUidAssetsAndOrdersAndSnapshotRestoresOriginalIdentity() throws Exception {
        jdbc.execute("ALTER TABLE yshop_user DROP INDEX uk_member_mobile,DROP INDEX uk_member_openid,DROP INDEX uk_member_routine_openid,MODIFY mobile CHAR(15) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL");
        long uid=member("+8613800000001","Legacy_Case"),oid=order(uid);String assets=jdbc.queryForMap("SELECT id,integral,now_money,brokerage_price FROM yshop_user WHERE id=?",uid).toString();
        jdbc.execute("CREATE TABLE quality_member_snapshot LIKE yshop_user");jdbc.execute("INSERT INTO quality_member_snapshot SELECT * FROM yshop_user");
        try{
            migration();assertEquals("13800000001",phone(uid));assertEquals("Legacy_Case",members.selectById(uid).getRoutineOpenid());assertEquals(assets,jdbc.queryForMap("SELECT id,integral,now_money,brokerage_price FROM yshop_user WHERE id=?",uid).toString());assertEquals(uid,jdbc.queryForObject("SELECT uid FROM yshop_store_order WHERE id=?",Long.class,oid));
            jdbc.execute("ALTER TABLE yshop_user DROP INDEX uk_member_mobile,DROP INDEX uk_member_openid,DROP INDEX uk_member_routine_openid,MODIFY mobile CHAR(15) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL");
            jdbc.execute("UPDATE yshop_user u JOIN quality_member_snapshot s ON u.id=s.id SET u.mobile=s.mobile,u.openid=s.openid,u.routine_openid=s.routine_openid");
            assertEquals("+8613800000001",phone(uid));assertEquals(assets,jdbc.queryForMap("SELECT id,integral,now_money,brokerage_price FROM yshop_user WHERE id=?",uid).toString());assertEquals(uid,jdbc.queryForObject("SELECT uid FROM yshop_store_order WHERE id=?",Long.class,oid));
            assertThrows(IllegalStateException.class,()->new co.yixiang.yshop.module.member.service.auth.MemberIdentitySchemaGuard(jdbc,false,"synthetic-mini","synthetic-mp").verify());
        }finally{jdbc.execute("DROP TABLE quality_member_snapshot");}
    }
}
