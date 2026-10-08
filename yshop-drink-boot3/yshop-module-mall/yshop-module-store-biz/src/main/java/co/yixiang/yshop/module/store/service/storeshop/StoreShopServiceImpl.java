package co.yixiang.yshop.module.store.service.storeshop;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import co.yixiang.yshop.framework.common.exception.ErrorCode;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import co.yixiang.yshop.module.store.controller.admin.storeshop.vo.*;
import co.yixiang.yshop.module.store.dal.dataobject.storeshop.StoreShopDO;
import co.yixiang.yshop.framework.common.pojo.PageResult;

import co.yixiang.yshop.module.store.convert.storeshop.StoreShopConvert;
import co.yixiang.yshop.module.store.dal.mysql.storeshop.StoreShopMapper;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.module.store.enums.ErrorCodeConstants.*;

/**
 * 门店管理 Service 实现类
 *
 * @author yshop
 */
@Service
@Validated
public class StoreShopServiceImpl implements StoreShopService {

    @jakarta.annotation.Resource
    private co.yixiang.yshop.module.store.service.storeshop.StoreAccessService storeAccess;

    @Resource
    private StoreShopMapper shopMapper;

    @Override
    public Long createShop(StoreShopCreateReqVO createReqVO) {
        storeAccess.requireHeadquarters();
        validateAssignments(createReqVO.getAdminId());
        // 插入
        StoreShopDO shop = StoreShopConvert.INSTANCE.convert(createReqVO);
        Integer status = createReqVO.getStatus();
        if(status==null || (status!=0 && status!=1)) throw new IllegalArgumentException("INVALID_STORE_STATUS");
        shop.setStatus(status);
        shopMapper.insert(shop);
        // 返回
        return shop.getId();
    }

    @Override
    public void updateShop(StoreShopUpdateReqVO updateReqVO) {
        storeAccess.requireShop(updateReqVO.getId());
        if(!storeAccess.headquarters() && !java.util.Objects.equals(shopMapper.selectById(updateReqVO.getId()).getAdminId(),updateReqVO.getAdminId()))
            throw new org.springframework.security.access.AccessDeniedException("STAFF_ASSIGNMENT_REQUIRES_HEADQUARTERS");

        validateAssignments(updateReqVO.getAdminId());
        // 校验存在
        validateShopExists(updateReqVO.getId());
        // 更新
        StoreShopDO updateObj = StoreShopConvert.INSTANCE.convert(updateReqVO);
        Integer status = updateReqVO.getStatus();
        if(status==null || (status!=0 && status!=1)) throw new IllegalArgumentException("INVALID_STORE_STATUS");
        updateObj.setStatus(status);
        shopMapper.updateById(updateObj);
    }


    private void validateAssignments(List<String> ids) {
        if(ids==null || ids.stream().anyMatch(id -> id==null || !id.matches("[1-9][0-9]{0,17}")))
            throw new IllegalArgumentException("INVALID_STORE_ASSIGNMENT");
    }

    @Override
    public void deleteShop(Long id) {
        // 校验存在
        storeAccess.requireHeadquarters();
        validateShopExists(id);
        // 删除
        shopMapper.deleteById(id);
    }

    private void validateShopExists(Long id) {
        if (shopMapper.selectById(id) == null) {
            throw exception(SHOP_NOT_EXISTS);
        }
    }

    @Override
    public StoreShopDO getShop(Long id) {
        storeAccess.requireShop(id);
        return shopMapper.selectById(id);
    }

    @Override
    public List<StoreShopDO> getShopList() {
        var allowed=storeAccess.allowedShopIds();
        LambdaQueryWrapper<StoreShopDO> wrapper = new LambdaQueryWrapper<>();
        if(allowed!=null) wrapper.in(StoreShopDO::getId,allowed);
        return shopMapper.selectList(wrapper);
    }

    @Override
    public PageResult<StoreShopDO> getShopPage(StoreShopPageReqVO pageReqVO) {
        return shopMapper.selectPage(pageReqVO,storeAccess.allowedShopIds());
    }

    @Override
    public List<StoreShopDO> getShopList(StoreShopExportReqVO exportReqVO) {
        return shopMapper.selectList(exportReqVO,storeAccess.allowedShopIds());
    }

}
