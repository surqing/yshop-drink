package co.yixiang.yshop.module.member.controller.app.social;

import co.yixiang.yshop.framework.common.enums.UserTypeEnum;
import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.module.member.controller.app.social.vo.AppSocialUserBindReqVO;
import co.yixiang.yshop.module.member.controller.app.social.vo.AppSocialUserUnbindReqVO;
import co.yixiang.yshop.module.member.convert.social.SocialUserConvert;
import co.yixiang.yshop.module.system.api.social.SocialUserApi;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


import static co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 App - 社交用户")
@RestController
@RequestMapping("/system/social-user")
@Validated
public class AppSocialUserController {

    @Resource
    private co.yixiang.yshop.module.member.service.auth.MemberAuthService authService;

    @co.yixiang.yshop.framework.security.core.annotations.PreAuthenticated
    @PostMapping("/bind")
    @Operation(summary = "社交绑定，使用 code 授权码")
    public CommonResult<Boolean> socialBind(@RequestBody @Valid AppSocialUserBindReqVO reqVO) {
        authService.changeSocialIdentity(getLoginUserId(),reqVO.getType(),reqVO.getCode(),reqVO.getSmsCode(),false);
        return CommonResult.success(true);
    }

    @co.yixiang.yshop.framework.security.core.annotations.PreAuthenticated
    @DeleteMapping("/unbind")
    @Operation(summary = "取消社交绑定")
    public CommonResult<Boolean> socialUnbind(@RequestBody @Valid AppSocialUserUnbindReqVO reqVO) {
        authService.changeSocialIdentity(getLoginUserId(),reqVO.getType(),reqVO.getCode(),reqVO.getSmsCode(),true);
        return CommonResult.success(true);
    }

}
