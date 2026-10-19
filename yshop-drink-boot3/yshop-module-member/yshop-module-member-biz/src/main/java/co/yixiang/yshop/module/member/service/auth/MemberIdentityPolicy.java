package co.yixiang.yshop.module.member.service.auth;

import co.yixiang.yshop.framework.common.util.validation.ValidationUtils;
import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception0;

/** Canonical member identity values. Provider identifiers are scoped by the configured SDK. */
public final class MemberIdentityPolicy {
    private MemberIdentityPolicy() {}
    public static String phone(String value) {
        if (!ValidationUtils.isMobile(value)) throw exception0(400,"手机号格式不正确");
        return value.startsWith("+86") ? value.substring(3) : value.startsWith("0086") ? value.substring(4) : value;
    }
    public static String providerId(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_-]{1,100}")) throw conflict();
        return value;
    }
    public static boolean complete(co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO user) {
        return user!=null && user.getMobile()!=null && user.getMobile().length()==11
                && user.getMobile().matches("[0-9]{11}") && ValidationUtils.isMobile(user.getMobile())
                && user.getRoutineOpenid()!=null && user.getRoutineOpenid().matches("[A-Za-z0-9_-]{1,100}");
    }
    public static co.yixiang.yshop.framework.common.exception.ServiceException completionRequired() {
        return exception0(400,"请前往小程序完成手机号与微信双重验证");
    }
    public static co.yixiang.yshop.framework.common.exception.ServiceException conflict() {
        return exception0(400,"身份凭据冲突，请使用本人已绑定身份登录");
    }
    public static void exactlyOne(int rows) {if(rows!=1)throw conflict();}
}
