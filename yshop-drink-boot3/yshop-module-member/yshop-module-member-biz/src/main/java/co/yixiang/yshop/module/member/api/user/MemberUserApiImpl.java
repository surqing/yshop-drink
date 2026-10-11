package co.yixiang.yshop.module.member.api.user;

import cn.hutool.core.util.IdUtil;
import co.yixiang.yshop.framework.common.enums.CommonStatusEnum;
import co.yixiang.yshop.framework.common.enums.ShopCommonEnum;
import co.yixiang.yshop.module.member.api.user.dto.MemberUserRespDTO;
import co.yixiang.yshop.module.member.api.user.dto.WechatUserDto;
import co.yixiang.yshop.module.member.convert.user.UserConvert;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.service.user.MemberUserService;
import jakarta.annotation.Resource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Collection;
import java.util.List;

/**
 * 会员用户的 API 实现类
 *
 * @author yshop
 */
@Service
@Validated
public class MemberUserApiImpl implements MemberUserApi {

    @Resource
    private MemberUserService userService;
    @Override
    public MemberUserRespDTO getUser(Long id) {
        MemberUserDO user = userService.getUser(id);
        return UserConvert.INSTANCE.convert2(user);
    }

    @Override
    public List<MemberUserRespDTO> getUsers(Collection<Long> ids) {
        return UserConvert.INSTANCE.convertList2(userService.getUserList(ids));
    }

    @Override
    public List<MemberUserRespDTO> getUserListByNickname(String nickname) {
        return UserConvert.INSTANCE.convertList2(userService.getUserListByNickname(nickname));
    }

    @Override
    public MemberUserRespDTO getUserByMobile(String mobile) {
        return UserConvert.INSTANCE.convert2(userService.getUserByMobile(mobile));
    }

}
