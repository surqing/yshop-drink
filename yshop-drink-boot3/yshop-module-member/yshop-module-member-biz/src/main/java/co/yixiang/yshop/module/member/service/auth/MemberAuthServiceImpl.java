package co.yixiang.yshop.module.member.service.auth;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import co.yixiang.yshop.framework.common.enums.CommonStatusEnum;
import co.yixiang.yshop.framework.common.enums.UserTypeEnum;
import co.yixiang.yshop.framework.common.util.monitor.TracerUtils;
import co.yixiang.yshop.framework.common.util.servlet.ServletUtils;
import co.yixiang.yshop.module.member.controller.app.auth.vo.*;
import co.yixiang.yshop.module.member.convert.auth.AuthConvert;
import co.yixiang.yshop.module.member.convert.user.UserConvert;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.enums.LoginTypeEnum;
import co.yixiang.yshop.module.member.service.user.MemberUserService;
import co.yixiang.yshop.module.system.api.logger.LoginLogApi;
import co.yixiang.yshop.module.system.api.logger.dto.LoginLogCreateReqDTO;
import co.yixiang.yshop.module.system.api.oauth2.OAuth2TokenApi;
import co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenCreateReqDTO;
import co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenRespDTO;
import co.yixiang.yshop.module.system.api.sms.SmsCodeApi;
import co.yixiang.yshop.module.system.enums.logger.LoginLogTypeEnum;
import co.yixiang.yshop.module.system.enums.logger.LoginResultEnum;
import co.yixiang.yshop.module.system.enums.oauth2.OAuth2ClientConstants;
import co.yixiang.yshop.module.system.enums.sms.SmsSceneEnum;
import co.yixiang.yshop.module.system.enums.social.SocialTypeEnum;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.framework.common.util.servlet.ServletUtils.getClientIP;
import static co.yixiang.yshop.module.member.enums.ErrorCodeConstants.*;

/**
 * 会员的认证 Service 接口
 *
 * @author yshop
 */
@Service
@Slf4j
public class MemberAuthServiceImpl implements MemberAuthService {

    @Resource
    private MemberUserService userService;
    @Resource
    private SmsCodeApi smsCodeApi;
    @Resource
    private LoginLogApi loginLogApi;
    @Resource
    private OAuth2TokenApi oauth2TokenApi;

    @Resource
    private WxMaService wxMaService;
    @Resource
    private WxMpService mpService;

    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    private MemberUserMapper userMapper;

    @Resource private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private <T> T attempt(java.util.function.Supplier<T> work) {
        for(int attempt=0;attempt<2;attempt++) {
            var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            try {return transaction.execute(status->work.get());}
            catch(org.springframework.dao.DuplicateKeyException conflict) {
                if(!identityUniqueConflict(conflict))throw conflict;
                // execute has fully rolled back SMS, token SQL and identity before re-reading owners.
                if(attempt==1)throw MemberIdentityPolicy.conflict();
            }
        }
        throw MemberIdentityPolicy.conflict();
    }
    private boolean identityUniqueConflict(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException sql && sql.getErrorCode()==1062) {
                String text=sql.getMessage();
                return text!=null && (text.contains("uk_member_mobile")||text.contains("uk_member_openid")||text.contains("uk_member_routine_openid"));
            }
        }
        return false;
    }

    @Override public AppAuthLoginRespVO login(AppAuthLoginReqVO request) {
        String phone=MemberIdentityPolicy.phone(request.getMobile());
        try {
            return attempt(()->createTokenAfterLoginSuccess(login0(phone,request.getPassword()),phone,LoginLogTypeEnum.LOGIN_MOBILE));
        } catch (PasswordLoginFailure rejected) {
            // Authentication has rolled back before the failure audit starts its own transaction.
            // Successful audits still commit atomically with token SQL and login metadata.
            attempt(()->{createLoginLog(rejected.userId,phone,LoginLogTypeEnum.LOGIN_MOBILE,rejected.result);return true;});
            throw rejected.error;
        }
    }
    private static final class PasswordLoginFailure extends RuntimeException {
        final Long userId;
        final LoginResultEnum result;
        final co.yixiang.yshop.framework.common.exception.ServiceException error;
        PasswordLoginFailure(Long userId,LoginResultEnum result) {
            this.userId=userId;this.result=result;
            this.error=exception(result==LoginResultEnum.USER_DISABLED?AUTH_LOGIN_USER_DISABLED:AUTH_LOGIN_BAD_CREDENTIALS);
        }
    }
    @Override public AppAuthLoginRespVO smsLogin(AppAuthSmsLoginReqVO request) {
        String phone=MemberIdentityPolicy.phone(request.getMobile());
        return attempt(()->{
            smsCodeApi.useSmsCode(new co.yixiang.yshop.module.system.api.sms.dto.code.SmsCodeUseReqDTO()
                    .setMobile(phone).setCode(request.getCode()).setScene(1).setUsedIp(getClientIP()));
            MemberUserDO user=claimMember(phone,null,true);
            var result=createTokenAfterLoginSuccess(user,phone,LoginLogTypeEnum.LOGIN_SMS);
            result.setUserInfo(UserConvert.INSTANCE.convert3(user));return result;
        });
    }
    /** Both proofs come from this configured Mini App SDK, before acquiring database locks. */
    @Override public AppAuthLoginRespVO weixinMiniAppLogin(AppAuthWeixinMiniAppLoginReqVO request) {
        String phone,identity;
        try {
            phone=MemberIdentityPolicy.phone(wxMaService.getUserService().getNewPhoneNoInfo(request.getPhoneCode()).getPurePhoneNumber());
            identity=miniIdentity(request.getLoginCode());
        }catch(WxErrorException failure){throw exception(AUTH_WEIXIN_MINI_APP_PHONE_CODE_ERROR);}
        return attempt(()->providerLogin(claimMember(phone,identity,true),identity));
    }
    @Override public AppAuthLoginRespVO weixinMiniAppLogin2(AppWeixinMiniLoginVO request) {
        final String identity;
        try {identity=miniIdentity(request.getCode());}catch(WxErrorException failure){throw exception(MINI_AUTH_LOGIN_BAD);}
        return attempt(()->{
            var hint=userMapper.selectByProvider(identity,true);
            var result=new AppAuthLoginRespVO();
            if(hint!=null){
                var current=requireEnabled(userMapper.lockById(hint.getId()));
                if(!identity.equals(current.getRoutineOpenid()))throw MemberIdentityPolicy.conflict();
                result=providerLogin(current,identity);
            }
            result.setOpenId(identity);return result;
        });
    }
    @Override public AppAuthLoginRespVO wechatAuth(String code) {
        final String identity;
        try {identity=MemberIdentityPolicy.providerId(mpService.getOAuth2Service().getAccessToken(code).getOpenId());}
        catch(WxErrorException failure){throw exception(MINI_AUTH_LOGIN_BAD);}
        return attempt(()->providerLogin(claimMember(null,identity,false),identity));
    }
    private String miniIdentity(String code) throws WxErrorException {
        if(StrUtil.isBlank(code))throw MemberIdentityPolicy.conflict();
        return MemberIdentityPolicy.providerId(wxMaService.getUserService().getSessionInfo(code).getOpenid());
    }
    private String providerIdentity(int type,String code) {
        try {
            if(type==34)return miniIdentity(code);
            if(type==31 && StrUtil.isNotBlank(code))return MemberIdentityPolicy.providerId(mpService.getOAuth2Service().getAccessToken(code).getOpenId());
            throw MemberIdentityPolicy.conflict();
        }catch(WxErrorException failure){throw exception(MINI_AUTH_LOGIN_BAD);}
    }
    private MemberUserDO requireEnabled(MemberUserDO user) {
        if(user==null || !Integer.valueOf(0).equals(user.getStatus()))throw exception(AUTH_LOGIN_USER_DISABLED);
        return user;
    }
    private AppAuthLoginRespVO providerLogin(MemberUserDO user,String identity) {
        var result=createTokenAfterLoginSuccess(requireEnabled(user),user.getMobile(),LoginLogTypeEnum.LOGIN_SOCIAL);
        result.setOpenId(identity);result.setUserInfo(UserConvert.INSTANCE.convert3(user));return result;
    }
    private MemberUserDO claimMember(String phone,String identity,boolean mini) {
        var phoneOwner=phone==null?null:userMapper.selectByMobile(phone);
        var providerOwner=identity==null?null:userMapper.selectByProvider(identity,mini);
        if(phoneOwner!=null && providerOwner!=null && !phoneOwner.getId().equals(providerOwner.getId()))throw MemberIdentityPolicy.conflict();
        var hint=phoneOwner!=null?phoneOwner:providerOwner;
        if(hint==null) {
            if(phone!=null)hint=userService.createUserIfAbsent(phone,getClientIP(),mini&&identity!=null?"routine":"h5");
            else {
                hint=new MemberUserDO().setUsername("member_"+cn.hutool.core.util.IdUtil.fastSimpleUUID())
                        .setNickname("yshop用户").setStatus(0).setPassword(passwordEncoder.encode(cn.hutool.core.util.IdUtil.fastSimpleUUID()))
                        .setRegisterIp(getClientIP()).setLoginType("wechat").setOpenid(identity);
                MemberIdentityPolicy.exactlyOne(userMapper.insert(hint));
            }
        }
        var user=requireEnabled(userMapper.lockById(hint.getId()));
        String stored=mini?user.getRoutineOpenid():user.getOpenid();
        if(phone!=null && user.getMobile()!=null && !phone.equals(user.getMobile()))throw MemberIdentityPolicy.conflict();
        if(identity!=null && stored!=null && !identity.equals(stored))throw MemberIdentityPolicy.conflict();
        // The unique indexes arbitrate claims absent from a non-locking owner lookup.
        if(phone!=null && user.getMobile()==null)MemberIdentityPolicy.exactlyOne(userMapper.changeMobile(user.getId(),null,phone));
        if(identity!=null && stored==null)MemberIdentityPolicy.exactlyOne(userMapper.changeProvider(user.getId(),null,identity,mini));
        return userMapper.selectById(user.getId());
    }
    @Override public void changeSocialIdentity(Long uid,Integer type,String code,String smsCode,boolean unbind) {
        if(uid==null || type==null || smsCode==null || !smsCode.matches("[0-9]{4,6}"))throw MemberIdentityPolicy.conflict();
        String verified=providerIdentity(type,code);
        attempt(()->{
            var user=requireEnabled(userMapper.lockById(uid));
            String phone=MemberIdentityPolicy.phone(user.getMobile());boolean mini=type==34;
            String stored=mini?user.getRoutineOpenid():user.getOpenid();
            var owner=userMapper.selectByProvider(verified,mini);
            if(owner!=null && !uid.equals(owner.getId()))throw MemberIdentityPolicy.conflict();
            if(unbind ? !verified.equals(stored) : stored!=null && !verified.equals(stored))throw MemberIdentityPolicy.conflict();
            smsCodeApi.useSmsCode(new co.yixiang.yshop.module.system.api.sms.dto.code.SmsCodeUseReqDTO()
                    .setMobile(phone).setCode(smsCode).setScene(unbind?6:5).setUsedIp(getClientIP()));
            if(unbind || stored==null)MemberIdentityPolicy.exactlyOne(userMapper.changeProvider(uid,stored,unbind?null:verified,mini));
            oauth2TokenApi.revokeUserTokens(uid,1);return true;
        });
    }

    private AppAuthLoginRespVO createTokenAfterLoginSuccess(MemberUserDO user, String mobile, LoginLogTypeEnum logType) {
        // All login mechanisms must enforce current server-side member status before issuing
        // tokens or recording a successful login. Password login also checks this earlier.
        if (user == null || !Objects.equals(user.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            throw exception(AUTH_LOGIN_USER_DISABLED);
        }
        // 插入登陆日志

        // 创建 Token 令牌
        OAuth2AccessTokenRespDTO accessTokenRespDTO = oauth2TokenApi.createAccessToken(new OAuth2AccessTokenCreateReqDTO()
                .setUserId(user.getId()).setUserType(getUserType().getValue())
                .setClientId(OAuth2ClientConstants.CLIENT_ID_DEFAULT));
        createLoginLog(user.getId(), mobile, logType, LoginResultEnum.SUCCESS);
        // 构建返回结果
        return AuthConvert.INSTANCE.convert(accessTokenRespDTO);
    }

//    @Override
//    public String getSocialAuthorizeUrl(Integer type, String redirectUri) {
//        return socialUserApi.getAuthorizeUrl(type, redirectUri);
//    }

    private MemberUserDO login0(String mobile, String password) {
        // 校验账号是否存在
        MemberUserDO user = userService.getUserByMobile(mobile);
        if (user == null) {
            throw new PasswordLoginFailure(null,LoginResultEnum.BAD_CREDENTIALS);
        }
        if (!userService.isPasswordMatch(password, user.getPassword())) {
            throw new PasswordLoginFailure(user.getId(),LoginResultEnum.BAD_CREDENTIALS);
        }
        // 校验是否禁用
        if (ObjectUtil.notEqual(user.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            throw new PasswordLoginFailure(user.getId(),LoginResultEnum.USER_DISABLED);
        }
        // Lock and recheck the credential snapshot before token family locks.
        var current=userMapper.lockById(user.getId());
        if(current==null || !mobile.equals(current.getMobile()) || !Objects.equals(user.getPassword(),current.getPassword()))throw new PasswordLoginFailure(user.getId(),LoginResultEnum.BAD_CREDENTIALS);
        if(!Objects.equals(current.getStatus(),CommonStatusEnum.ENABLE.getStatus()))throw new PasswordLoginFailure(user.getId(),LoginResultEnum.USER_DISABLED);
        return current;
    }

    private void createLoginLog(Long userId, String mobile, LoginLogTypeEnum logType, LoginResultEnum loginResult) {
        // 插入登录日志
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(logType.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(getUserType().getValue());
        reqDTO.setUsername(mobile);
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(getClientIP());
        reqDTO.setResult(loginResult.getResult());
        loginLogApi.createLoginLog(reqDTO);
        // 更新最后登录时间
        if (userId != null && Objects.equals(LoginResultEnum.SUCCESS.getResult(), loginResult.getResult())) {
            userService.updateUserLogin(userId, getClientIP());
        }
    }

    @Override
    public void logout(String token) {
        // 删除访问令牌
        OAuth2AccessTokenRespDTO accessTokenRespDTO = oauth2TokenApi.removeAccessToken(token, UserTypeEnum.MEMBER.getValue());
        if (accessTokenRespDTO == null) {
            return;
        }
        // 删除成功，则记录登出日志
        createLogoutLog(accessTokenRespDTO.getUserId());
    }

    @Override
    public void updatePassword(Long userId, AppAuthUpdatePasswordReqVO reqVO) {
        // 检验旧密码
        MemberUserDO userDO = checkOldPassword(userId, reqVO.getOldPassword());

        // 更新用户密码
        // TODO yshop：需要重构到用户模块
        userMapper.updateById(MemberUserDO.builder().id(userDO.getId())
                .password(passwordEncoder.encode(reqVO.getPassword())).build());
    }

//    @Override
//    public void resetPassword(AppAuthResetPasswordReqVO reqVO) {
//        // 检验用户是否存在
//        MemberUserDO userDO = checkUserIfExists(reqVO.getMobile());
//
//        // 使用验证码
//        smsCodeApi.useSmsCode(AuthConvert.INSTANCE.convert(reqVO, SmsSceneEnum.MEMBER_FORGET_PASSWORD,
//                getClientIP()));
//
//        // 更新密码
//        userMapper.updateById(MemberUserDO.builder().id(userDO.getId())
//                .password(passwordEncoder.encode(reqVO.getPassword())).build());
//    }

    @Override
    public void sendSmsCode(Long userId, AppAuthSmsSendReqVO reqVO) {
        Integer scene=reqVO.getScene();
        if(scene==null || scene<1 || scene>6)throw MemberIdentityPolicy.conflict();
        String phone;
        if(scene==5 || scene==6) {
            if(userId==null)throw MemberIdentityPolicy.conflict();
            phone=MemberIdentityPolicy.phone(requireEnabled(userMapper.selectById(userId)).getMobile());
        }else {
            if(scene==2 || scene==3) {if(userId==null)throw MemberIdentityPolicy.conflict();requireEnabled(userMapper.selectById(userId));}
            phone=MemberIdentityPolicy.phone(reqVO.getMobile());
        }
        smsCodeApi.sendSmsCode(new co.yixiang.yshop.module.system.api.sms.dto.code.SmsCodeSendReqDTO().setMobile(phone).setScene(scene).setCreateIp(getClientIP()));
    }

    @Override
    public AppAuthLoginRespVO refreshToken(String refreshToken) {
        OAuth2AccessTokenRespDTO accessTokenDO = oauth2TokenApi.refreshAccessToken(refreshToken, OAuth2ClientConstants.CLIENT_ID_DEFAULT, UserTypeEnum.MEMBER.getValue());
        return AuthConvert.INSTANCE.convert(accessTokenDO);
    }

    /**
     * 校验旧密码
     *
     * @param id          用户 id
     * @param oldPassword 旧密码
     * @return MemberUserDO 用户实体
     */
    @VisibleForTesting
    public MemberUserDO checkOldPassword(Long id, String oldPassword) {
        MemberUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        // 参数：未加密密码，编码后的密码
        if (!passwordEncoder.matches(oldPassword,user.getPassword())) {
            throw exception(USER_PASSWORD_FAILED);
        }
        return user;
    }

    public MemberUserDO checkUserIfExists(String mobile) {
        MemberUserDO user = userMapper.selectByMobile(mobile);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        return user;
    }

    private void createLogoutLog(Long userId) {
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(LoginLogTypeEnum.LOGOUT_SELF.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(getUserType().getValue());
        reqDTO.setUsername(getMobile(userId));
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(getClientIP());
        reqDTO.setResult(LoginResultEnum.SUCCESS.getResult());
        loginLogApi.createLoginLog(reqDTO);
    }

    private String getMobile(Long userId) {
        if (userId == null) {
            return null;
        }
        MemberUserDO user = userService.getUser(userId);
        return user != null ? user.getMobile() : null;
    }

    private UserTypeEnum getUserType() {
        return UserTypeEnum.MEMBER;
    }

}
