package co.yixiang.yshop.module.member.dal.mysql.useraddress;

import java.util.*;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.mybatis.core.query.LambdaQueryWrapperX;
import co.yixiang.yshop.framework.mybatis.core.mapper.BaseMapperX;
import co.yixiang.yshop.module.member.dal.dataobject.useraddress.UserAddressDO;
import org.apache.ibatis.annotations.Mapper;
import co.yixiang.yshop.module.member.controller.admin.useraddress.vo.*;

/**
 * 用户地址 Mapper
 *
 * @author yshop
 */
@Mapper
public interface UserAddressMapper extends BaseMapperX<UserAddressDO> {

    default UserAddressDO selectOwned(Long uid, Long addressId) {
        return selectOne(new LambdaQueryWrapperX<UserAddressDO>()
                .eq(UserAddressDO::getId, addressId).eq(UserAddressDO::getUid, uid));
    }

    default int updateOwned(Long uid, Long addressId, UserAddressDO update) {
        return update(update, new LambdaQueryWrapperX<UserAddressDO>()
                .eq(UserAddressDO::getId, addressId).eq(UserAddressDO::getUid, uid));
    }

    default int deleteOwned(Long uid, Long addressId) {
        return delete(new LambdaQueryWrapperX<UserAddressDO>()
                .eq(UserAddressDO::getId, addressId).eq(UserAddressDO::getUid, uid));
    }

    default void clearDefaults(Long uid) {
        update(UserAddressDO.builder().isDefault(0).build(),
                new LambdaQueryWrapperX<UserAddressDO>().eq(UserAddressDO::getUid, uid));
    }

    default PageResult<UserAddressDO> selectPage(UserAddressPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<UserAddressDO>()
                .eqIfPresent(UserAddressDO::getUid, reqVO.getUid())
                .likeIfPresent(UserAddressDO::getRealName, reqVO.getRealName())
                .eqIfPresent(UserAddressDO::getPhone, reqVO.getPhone())
                .eqIfPresent(UserAddressDO::getProvince, reqVO.getProvince())
                .eqIfPresent(UserAddressDO::getCity, reqVO.getCity())
                .eqIfPresent(UserAddressDO::getCityId, reqVO.getCityId())
                .eqIfPresent(UserAddressDO::getDistrict, reqVO.getDistrict())
                .eqIfPresent(UserAddressDO::getDetail, reqVO.getDetail())
                .eqIfPresent(UserAddressDO::getPostCode, reqVO.getPostCode())
                .eqIfPresent(UserAddressDO::getLongitude, reqVO.getLongitude())
                .eqIfPresent(UserAddressDO::getLatitude, reqVO.getLatitude())
                .eqIfPresent(UserAddressDO::getIsDefault, reqVO.getIsDefault())
                .betweenIfPresent(UserAddressDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(UserAddressDO::getId));
    }

    default List<UserAddressDO> selectList(UserAddressExportReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<UserAddressDO>()
                .eqIfPresent(UserAddressDO::getUid, reqVO.getUid())
                .likeIfPresent(UserAddressDO::getRealName, reqVO.getRealName())
                .eqIfPresent(UserAddressDO::getPhone, reqVO.getPhone())
                .eqIfPresent(UserAddressDO::getProvince, reqVO.getProvince())
                .eqIfPresent(UserAddressDO::getCity, reqVO.getCity())
                .eqIfPresent(UserAddressDO::getCityId, reqVO.getCityId())
                .eqIfPresent(UserAddressDO::getDistrict, reqVO.getDistrict())
                .eqIfPresent(UserAddressDO::getDetail, reqVO.getDetail())
                .eqIfPresent(UserAddressDO::getPostCode, reqVO.getPostCode())
                .eqIfPresent(UserAddressDO::getLongitude, reqVO.getLongitude())
                .eqIfPresent(UserAddressDO::getLatitude, reqVO.getLatitude())
                .eqIfPresent(UserAddressDO::getIsDefault, reqVO.getIsDefault())
                .betweenIfPresent(UserAddressDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(UserAddressDO::getId));
    }

}
