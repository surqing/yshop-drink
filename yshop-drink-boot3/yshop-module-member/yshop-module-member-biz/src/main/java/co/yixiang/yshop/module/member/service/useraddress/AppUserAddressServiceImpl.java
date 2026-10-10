package co.yixiang.yshop.module.member.service.useraddress;

import cn.hutool.core.util.StrUtil;
import co.yixiang.yshop.framework.common.enums.ShopCommonEnum;
import co.yixiang.yshop.module.member.controller.app.address.param.AppAddressParam;
import co.yixiang.yshop.module.member.controller.app.address.vo.AppUserAddressQueryVo;
import co.yixiang.yshop.module.member.convert.useraddress.UserAddressConvert;
import co.yixiang.yshop.module.member.dal.dataobject.useraddress.UserAddressDO;
import co.yixiang.yshop.module.member.dal.mysql.useraddress.UserAddressMapper;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.module.member.enums.ErrorCodeConstants.USER_ADDRESS_NOT_EXISTS;
import static co.yixiang.yshop.module.member.enums.ErrorCodeConstants.USER_ADDRESS_PARAM_NOT_EXISTS;
import static co.yixiang.yshop.module.member.enums.ErrorCodeConstants.USER_NOT_EXISTS;

/**
 * 用户地址 Service 实现类
 *
 * @author yshop
 */
@Service
@Validated
public class AppUserAddressServiceImpl extends ServiceImpl<UserAddressMapper,UserAddressDO> implements AppUserAddressService {

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private MemberUserMapper memberUserMapper;

    private Long parseAddressId(String id) {
        if (id == null || !id.matches("[0-9]+")) {
            throw exception(USER_ADDRESS_PARAM_NOT_EXISTS);
        }
        try {
            long value = Long.parseLong(id);
            if (value <= 0) throw exception(USER_ADDRESS_PARAM_NOT_EXISTS);
            return value;
        } catch (NumberFormatException invalid) {
            throw exception(USER_ADDRESS_PARAM_NOT_EXISTS);
        }
    }

    private void lockMember(Long uid) {
        if (uid == null || memberUserMapper.lockById(uid) == null) {
            throw exception(USER_NOT_EXISTS);
        }
    }

    @Override
    public UserAddressDO getOwnedAddress(Long uid, String addressId) {
        Long id = parseAddressId(addressId);
        UserAddressDO address = userAddressMapper.selectOwned(uid, id);
        if (uid == null || address == null) throw exception(USER_ADDRESS_NOT_EXISTS);
        return address;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void deleteOwnedAddress(Long uid, String addressId) {
        Long id = parseAddressId(addressId);
        lockMember(uid);
        if (userAddressMapper.deleteOwned(uid, id) != 1) throw exception(USER_ADDRESS_NOT_EXISTS);
    }

    /**
     * 添加或者修改地址
     * @param uid uid
     * @param param AddressParam
     * @return id 地址id
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Long addAndEdit(Long uid, AppAddressParam param){
        Long id = StrUtil.isBlank(param.getId()) ? null : parseAddressId(param.getId());
        lockMember(uid);
        if (id != null && userAddressMapper.selectOwned(uid, id) == null) {
            throw exception(USER_ADDRESS_NOT_EXISTS);
        }
        if (ShopCommonEnum.DEFAULT_1.getValue().equals(param.getIsDefault())) userAddressMapper.clearDefaults(uid);
        UserAddressDO userAddress = UserAddressDO.builder()
                .address(param.getAddress())
                .detail(param.getDetail())
                .isDefault(param.getIsDefault())
                .phone(param.getPhone())
                .postCode(param.getPostCode())
                .realName(param.getRealName())
                .latitude(param.getLatitude())
                .longitude(param.getLongitude())
                .build();
        if(id == null){
            userAddress.setUid(uid);
            if (userAddress.getIsDefault() == null) userAddress.setIsDefault(0);
            userAddressMapper.insert(userAddress);
        }else{
            userAddress.setId(id);
            if (userAddressMapper.updateOwned(uid, id, userAddress) != 1) throw exception(USER_ADDRESS_NOT_EXISTS);
        }

        return userAddress.getId();
    }

    /**
     * 设置默认地址
     * @param uid uid
     * @param addressId 地址id
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void setDefault(Long uid,String addressId){
        Long id = parseAddressId(addressId);
        lockMember(uid);
        if (userAddressMapper.selectOwned(uid, id) == null) throw exception(USER_ADDRESS_NOT_EXISTS);
        userAddressMapper.clearDefaults(uid);
        if (userAddressMapper.updateOwned(uid, id, UserAddressDO.builder().isDefault(1).build()) != 1) {
            throw exception(USER_ADDRESS_NOT_EXISTS);
        }
    }


    /**
     * 获取用户地址
     * @param uid uid
     * @param page page
     * @param limit limit
     * @return List
     */
    @Override
    public List<AppUserAddressQueryVo> getList(Long uid, int page, int limit){
        Page<UserAddressDO> pageModel = new Page<>(page, limit);
        IPage<UserAddressDO> pageList = this.lambdaQuery().eq(UserAddressDO::getUid,uid).page(pageModel);
        return UserAddressConvert.INSTANCE.convertList02(pageList.getRecords());
    }


}
