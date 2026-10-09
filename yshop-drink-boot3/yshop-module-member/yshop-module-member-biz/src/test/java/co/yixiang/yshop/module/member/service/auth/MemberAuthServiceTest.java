package co.yixiang.yshop.module.member.service.auth;

import co.yixiang.yshop.framework.common.exception.ServiceException;
import co.yixiang.yshop.framework.test.core.ut.BaseMockitoUnitTest;
import co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthUpdatePasswordReqVO;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.system.api.oauth2.OAuth2TokenApi;
import co.yixiang.yshop.module.system.api.logger.LoginLogApi;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Current password/auth contract, no live WeChat/Redis/database or obsolete reset invocation.
 * Prior reset test never called production code (the API was removed) and compared unrelated random
 * passwords. This replacement tests the supported update and rejects unauthorized persistence.
 */
public class MemberAuthServiceTest extends BaseMockitoUnitTest {
    @InjectMocks MemberAuthServiceImpl authService;
    @Mock MemberUserMapper userMapper;
    @Mock cn.binarywang.wx.miniapp.api.WxMaService wxMaService;
    @Mock cn.binarywang.wx.miniapp.api.WxMaUserService wxMaUserService;
    @Mock co.yixiang.yshop.module.member.dal.redis.order.MiniRedisDAO miniRedisDAO;
    @Mock co.yixiang.yshop.module.member.service.user.MemberUserService userService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock OAuth2TokenApi oauth2TokenApi;
    @Mock LoginLogApi loginLogApi;
    @Mock co.yixiang.yshop.module.system.api.sms.SmsCodeApi smsCodeApi;
    AppAuthUpdatePasswordReqVO request() {
        return AppAuthUpdatePasswordReqVO.builder().oldPassword("synthetic-old-plain")
                .password("synthetic-new-plain").build();
    }
    @Test void testUpdatePassword_success() {
        when(userMapper.selectById(7L)).thenReturn(MemberUserDO.builder().id(7L).password("synthetic-stored-hash").build());
        when(passwordEncoder.matches("synthetic-old-plain","synthetic-stored-hash")).thenReturn(true);
        when(passwordEncoder.encode("synthetic-new-plain")).thenReturn("synthetic-new-hash");
        authService.updatePassword(7L,request());
        ArgumentCaptor<MemberUserDO> value=ArgumentCaptor.forClass(MemberUserDO.class);
        verify(userMapper).updateById(value.capture());
        assertEquals(7L,value.getValue().getId());
        assertEquals("synthetic-new-hash",value.getValue().getPassword());
        assertNotEquals(request().getPassword(),value.getValue().getPassword());
    }
    @Test void wrongOldPasswordNeverWrites() {
        when(userMapper.selectById(7L)).thenReturn(MemberUserDO.builder().id(7L).password("synthetic-stored-hash").build());
        when(passwordEncoder.matches("synthetic-old-plain","synthetic-stored-hash")).thenReturn(false);
        assertThrows(ServiceException.class,()->authService.updatePassword(7L,request()));
        verify(userMapper,never()).updateById(any(MemberUserDO.class));
        verify(passwordEncoder,never()).encode(anyString());
    }
    @Test void missingMemberNeverWrites() {
        assertThrows(ServiceException.class,()->authService.updatePassword(7L,request()));
        verify(userMapper,never()).updateById(any(MemberUserDO.class));
        verifyNoInteractions(passwordEncoder);
    }
    @Test void encoderFailureNeverWrites() {
        when(userMapper.selectById(7L)).thenReturn(MemberUserDO.builder().id(7L).password("synthetic-stored-hash").build());
        when(passwordEncoder.matches(anyString(),anyString())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenThrow(new IllegalStateException("synthetic encoder failure"));
        assertThrows(IllegalStateException.class,()->authService.updatePassword(7L,request()));
        verify(userMapper,never()).updateById(any(MemberUserDO.class));
    }
    @Test void missingTokenLogoutIsIdempotentWithoutIdentityLookup() {
        authService.logout("synthetic-expired-token");
        verify(oauth2TokenApi).removeAccessToken("synthetic-expired-token",1);
        verifyNoInteractions(userMapper,loginLogApi);
    }
    void syntheticWechatMember(int status) throws Exception {
        var session = new cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult();
        session.setOpenid("synthetic-openid"); session.setSessionKey("synthetic-session-key");
        when(wxMaService.getUserService()).thenReturn(wxMaUserService);
        when(wxMaUserService.getSessionInfo("synthetic-login-code")).thenReturn(session);
        when(userMapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(MemberUserDO.builder().id(7L).status(status).mobile("synthetic-mobile").build());
    }
    @Test void disabledWechatMemberCannotReceiveToken() throws Exception {
        syntheticWechatMember(1);
        // Lenient response ensures the pre-fix test reaches an actual token grant rather than
        // failing incidentally because an unconfigured mock returns null.
        lenient().when(oauth2TokenApi.createAccessToken(any())).thenReturn(
                new co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenRespDTO()
                        .setUserId(7L).setAccessToken("synthetic-access-token"));
        assertThrows(ServiceException.class, () -> authService.weixinMiniAppLogin2(
                co.yixiang.yshop.module.member.controller.app.auth.vo.AppWeixinMiniLoginVO.builder()
                        .code("synthetic-login-code").build()));
        verify(oauth2TokenApi,never()).createAccessToken(any());
        verify(userService,never()).updateUserLogin(anyLong(),any());
    }
    @Test void enabledWechatMemberReceivesOnlyItsOwnIdentity() throws Exception {
        syntheticWechatMember(0);
        when(oauth2TokenApi.createAccessToken(any())).thenReturn(
                new co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenRespDTO()
                        .setUserId(7L).setAccessToken("synthetic-access-token"));
        var result=authService.weixinMiniAppLogin2(
                co.yixiang.yshop.module.member.controller.app.auth.vo.AppWeixinMiniLoginVO.builder()
                        .code("synthetic-login-code").build());
        assertEquals("synthetic-access-token",result.getAccessToken());
        assertEquals("synthetic-openid",result.getOpenId());
        verify(oauth2TokenApi).createAccessToken(argThat(v -> v.getUserId()==7L && v.getUserType()==1));
        verify(miniRedisDAO).set("synthetic-session-key","synthetic-openid");
    }
    @Test void expiredWechatSessionNeverDecryptsOrCreatesToken() {
        when(miniRedisDAO.get("synthetic-openid")).thenReturn(null);
        assertThrows(ServiceException.class,()->authService.weixinMiniAppLogin3("synthetic-data","synthetic-iv","synthetic-openid"));
        verifyNoInteractions(wxMaService,oauth2TokenApi,userMapper);
    }

    @Test void refreshIsMemberTypedAndNeverLogsSuccessfulLogin() {
        when(oauth2TokenApi.refreshAccessToken("synthetic-refresh","default",1)).thenReturn(
                new co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenRespDTO().setUserId(7L).setAccessToken("synthetic-next"));
        assertEquals("synthetic-next",authService.refreshToken("synthetic-refresh").getAccessToken());
        verify(oauth2TokenApi).refreshAccessToken("synthetic-refresh","default",1);
        verifyNoInteractions(loginLogApi,userMapper,userService);
    }
    @Test void rejectedRefreshNeverRecordsSuccessfulLoginOrUserUpdate() {
        when(oauth2TokenApi.refreshAccessToken("synthetic-revoked","default",1))
                .thenThrow(new ServiceException(401,"账户不可用"));
        assertThrows(ServiceException.class,()->authService.refreshToken("synthetic-revoked"));
        verifyNoInteractions(loginLogApi,userMapper,userService);
    }
    @Test void statusChangedBeforeTokenGrantCannotWriteSuccessLog() throws Exception {
        syntheticWechatMember(0);
        when(oauth2TokenApi.createAccessToken(any())).thenThrow(new ServiceException(401,"账户不可用"));
        assertThrows(ServiceException.class,()->authService.weixinMiniAppLogin2(
                co.yixiang.yshop.module.member.controller.app.auth.vo.AppWeixinMiniLoginVO.builder().code("synthetic-login-code").build()));
        verifyNoInteractions(loginLogApi);verify(userService,never()).updateUserLogin(anyLong(),any());
    }


    MemberUserDO activePasswordMember() {
        return MemberUserDO.builder().id(7L).mobile("13800000001").status(0).password("synthetic-hash").build();
    }
    co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthLoginReqVO passwordLoginRequest() {
        return co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthLoginReqVO.builder()
                .mobile("13800000001").password("synthetic-password").build();
    }
    void grantResponse() {
        when(oauth2TokenApi.createAccessToken(any())).thenReturn(new co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenRespDTO()
                .setUserId(7L).setAccessToken("synthetic-access"));
    }
    @Test void passwordLoginChecksIdentityAndWritesOnlySuccessAfterGrant() {
        when(userService.getUserByMobile("13800000001")).thenReturn(activePasswordMember());
        when(userService.isPasswordMatch("synthetic-password","synthetic-hash")).thenReturn(true);grantResponse();
        assertEquals("synthetic-access",authService.login(passwordLoginRequest()).getAccessToken());
        InOrder order=inOrder(oauth2TokenApi,loginLogApi,userService);
        order.verify(oauth2TokenApi).createAccessToken(argThat(v->v.getUserId()==7L && v.getUserType()==1));
        order.verify(loginLogApi).createLoginLog(argThat(v->v.getUserId()==7L && v.getResult()==0 && v.getUserType()==1));
        order.verify(userService).updateUserLogin(eq(7L),any());
    }
    @Test void missingPasswordAccountNeverGrantsOrRecordsSuccess() {
        assertThrows(ServiceException.class,()->authService.login(passwordLoginRequest()));
        verifyNoInteractions(oauth2TokenApi);verify(loginLogApi).createLoginLog(argThat(v->v.getUserId()==null && v.getResult()!=0));
        verify(userService,never()).updateUserLogin(anyLong(),any());
    }
    @Test void wrongPasswordNeverGrantsOrRecordsSuccess() {
        when(userService.getUserByMobile("13800000001")).thenReturn(activePasswordMember());
        assertThrows(ServiceException.class,()->authService.login(passwordLoginRequest()));
        verifyNoInteractions(oauth2TokenApi);verify(loginLogApi).createLoginLog(argThat(v->v.getResult()!=0));
        verify(userService,never()).updateUserLogin(anyLong(),any());
    }
    @Test void disabledPasswordMemberNeverGrantsOrRecordsSuccess() {
        var user=activePasswordMember();user.setStatus(1);when(userService.getUserByMobile("13800000001")).thenReturn(user);
        when(userService.isPasswordMatch("synthetic-password","synthetic-hash")).thenReturn(true);
        assertThrows(ServiceException.class,()->authService.login(passwordLoginRequest()));
        verifyNoInteractions(oauth2TokenApi);verify(loginLogApi).createLoginLog(argThat(v->v.getResult()!=0));
        verify(userService,never()).updateUserLogin(anyLong(),any());
    }
    @Test void passwordGrantRejectionDoesNotRecordSuccessfulLogin() {
        when(userService.getUserByMobile("13800000001")).thenReturn(activePasswordMember());
        when(userService.isPasswordMatch("synthetic-password","synthetic-hash")).thenReturn(true);
        when(oauth2TokenApi.createAccessToken(any())).thenThrow(new ServiceException(401,"synthetic revoked"));
        assertThrows(ServiceException.class,()->authService.login(passwordLoginRequest()));
        verifyNoInteractions(loginLogApi);verify(userService,never()).updateUserLogin(anyLong(),any());
    }
    @Test void successfulLogoutLogsLogoutNotLoginAndDoesNotUpdateLoginTime() {
        when(oauth2TokenApi.removeAccessToken("synthetic-access",1)).thenReturn(new co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenRespDTO().setUserId(7L));
        when(userService.getUser(7L)).thenReturn(activePasswordMember());authService.logout("synthetic-access");
        verify(loginLogApi).createLoginLog(argThat(v->v.getUserId()==7L && v.getUserType()==1
                && v.getLogType().equals(co.yixiang.yshop.module.system.enums.logger.LoginLogTypeEnum.LOGOUT_SELF.getType())));
        verify(userService,never()).updateUserLogin(anyLong(),any());
    }
    @Test void wrongIdentityLogoutNeverRecordsAnyLog() {
        when(oauth2TokenApi.removeAccessToken("synthetic-admin-access",1)).thenThrow(new ServiceException(401,"synthetic identity mismatch"));
        assertThrows(ServiceException.class,()->authService.logout("synthetic-admin-access"));
        verifyNoInteractions(loginLogApi,userService);
    }
    co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthSmsLoginReqVO smsRequest() {
        return co.yixiang.yshop.module.member.controller.app.auth.vo.AppAuthSmsLoginReqVO.builder()
                .mobile("13800000001").code("123456").from("h5").build();
    }
    @Test void invalidSmsNeverCreatesIdentityTokenOrSuccessLog() {
        doThrow(new ServiceException(401,"synthetic invalid code")).when(smsCodeApi).useSmsCode(any());
        assertThrows(ServiceException.class,()->authService.smsLogin(smsRequest()));
        verifyNoInteractions(oauth2TokenApi,userService,userMapper,loginLogApi);
    }
    @Test void disabledSmsMemberNeverGrantsOrWritesSuccess() {
        var user=activePasswordMember();user.setStatus(1);
        when(userService.createUserIfAbsent(eq("13800000001"),any(),eq("h5"))).thenReturn(user);
        assertThrows(ServiceException.class,()->authService.smsLogin(smsRequest()));
        verifyNoInteractions(oauth2TokenApi,loginLogApi);verify(userService,never()).updateUserLogin(anyLong(),any());
    }
    @Test void enabledSmsUsesVerifiedIdentityAndRecordsSuccessAfterGrant() {
        when(userService.createUserIfAbsent(eq("13800000001"),any(),eq("h5"))).thenReturn(activePasswordMember());grantResponse();
        assertEquals("synthetic-access",authService.smsLogin(smsRequest()).getAccessToken());
        InOrder order=inOrder(smsCodeApi,oauth2TokenApi,loginLogApi);
        order.verify(smsCodeApi).useSmsCode(any());order.verify(oauth2TokenApi).createAccessToken(argThat(v->v.getUserId()==7L && v.getUserType()==1));
        order.verify(loginLogApi).createLoginLog(argThat(v->v.getResult()==0));
    }
}
