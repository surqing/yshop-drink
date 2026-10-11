package co.yixiang.yshop.module.member.service.useraddress;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import co.yixiang.yshop.module.member.controller.admin.useraddress.vo.*;
import co.yixiang.yshop.module.member.dal.dataobject.useraddress.UserAddressDO;
import co.yixiang.yshop.framework.common.pojo.PageResult;

import co.yixiang.yshop.module.member.convert.useraddress.UserAddressConvert;
import co.yixiang.yshop.module.member.dal.mysql.useraddress.UserAddressMapper;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.module.member.enums.ErrorCodeConstants.*;

/**
 * 用户地址 Service 实现类
 *
 * @author yshop
 */
@Service
@Validated
public class UserAddressServiceImpl implements UserAddressService {

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private MemberUserMapper memberUserMapper;

    private void lockMember(Long uid) {
        if (uid == null || memberUserMapper.lockById(uid) == null) throw exception(USER_NOT_EXISTS);
    }

    private UserAddressDO lockOwner(Long addressId) {
        UserAddressDO address = userAddressMapper.selectById(addressId);
        if (address == null) throw exception(USER_ADDRESS_NOT_EXISTS);
        lockMember(address.getUid());
        address = userAddressMapper.selectOwned(address.getUid(), addressId);
        if (address == null) throw exception(USER_ADDRESS_NOT_EXISTS);
        return address;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Long createUserAddress(UserAddressCreateReqVO createReqVO) {
        lockMember(createReqVO.getUid());
        if (Integer.valueOf(1).equals(createReqVO.getIsDefault())) userAddressMapper.clearDefaults(createReqVO.getUid());
        UserAddressDO userAddress = UserAddressConvert.INSTANCE.convert(createReqVO);
        userAddressMapper.insert(userAddress);
        // 返回
        return userAddress.getId();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void updateUserAddress(UserAddressUpdateReqVO updateReqVO) {
        UserAddressDO current = lockOwner(updateReqVO.getId());
        if (!Objects.equals(current.getUid(), updateReqVO.getUid())) throw exception(USER_ADDRESS_PARAM_NOT_EXISTS);
        if (Integer.valueOf(1).equals(updateReqVO.getIsDefault())) userAddressMapper.clearDefaults(current.getUid());
        UserAddressDO updateObj = UserAddressConvert.INSTANCE.convert(updateReqVO);
        updateObj.setUid(null);
        if (userAddressMapper.updateOwned(current.getUid(), current.getId(), updateObj) != 1) throw exception(USER_ADDRESS_NOT_EXISTS);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void deleteUserAddress(Long id) {
        UserAddressDO current = lockOwner(id);
        if (userAddressMapper.deleteOwned(current.getUid(), id) != 1) throw exception(USER_ADDRESS_NOT_EXISTS);
    }

    @Override
    public UserAddressDO getUserAddress(Long id) {
        return userAddressMapper.selectById(id);
    }

    @Override
    public List<UserAddressDO> getUserAddressList(Collection<Long> ids) {
        return userAddressMapper.selectBatchIds(ids);
    }

    @Override
    public PageResult<UserAddressDO> getUserAddressPage(UserAddressPageReqVO pageReqVO) {
        return userAddressMapper.selectPage(pageReqVO);
    }

    @Override
    public List<UserAddressDO> getUserAddressList(UserAddressExportReqVO exportReqVO) {
        return userAddressMapper.selectList(exportReqVO);
    }

}
