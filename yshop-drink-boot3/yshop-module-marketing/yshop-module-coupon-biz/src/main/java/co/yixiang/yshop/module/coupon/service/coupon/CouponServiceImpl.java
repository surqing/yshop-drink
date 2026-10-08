package co.yixiang.yshop.module.coupon.service.coupon;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.mybatis.core.query.LambdaQueryWrapperX;
import co.yixiang.yshop.module.coupon.controller.admin.coupon.vo.*;
import co.yixiang.yshop.module.coupon.convert.coupon.CouponConvert;
import co.yixiang.yshop.module.coupon.dal.dataobject.coupon.CouponDO;
import co.yixiang.yshop.module.coupon.dal.mysql.coupon.CouponMapper;
import co.yixiang.yshop.module.coupon.service.marketing.CouponMarketingService;
import co.yixiang.yshop.module.coupon.service.marketing.CouponPolicy;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class CouponServiceImpl implements CouponService {
    @Resource private CouponMapper mapper;
    @Resource private CouponMarketingService marketing;
    private LambdaQueryWrapperX<CouponDO> scoped() {
        var w=new LambdaQueryWrapperX<CouponDO>();String scope=marketing.adminScopeRegex();
        if(scope!=null)w.apply("shop_id REGEXP {0}",scope);
        return w;
    }
    private CouponDO safe(CouponDO c) {if(c!=null){c.setExchangeCode(null);c.setRedemptionCodeHash(null);}return c;}
    @Override @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Long create(CouponCreateReqVO request) {
        var c=CouponConvert.INSTANCE.convert(request);c.setId(null);marketing.validate(c,null);mapper.insert(c);marketing.auditTemplate(c.getId(),"CREATE","创建活动，不发行到会员");return c.getId();
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void update(CouponUpdateReqVO request) {
        var old=marketing.requireTemplate(request.getId(),true);var c=CouponConvert.INSTANCE.convert(request);marketing.validate(c,old);mapper.updateById(c);marketing.auditTemplate(c.getId(),"UPDATE","模板变更仅影响未来领取，已领权益不变");
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void delete(Long id) {
        var old=marketing.requireTemplate(id,true);
        if(CouponPolicy.number(old,"receive")!=0 || marketing.statistics(id).get("CLAIMED")!=0)throw CouponPolicy.reject("COUPON_HAS_ISSUED_RIGHTS_DISABLE_INSTEAD");
        mapper.deleteById(id);marketing.auditTemplate(id,"DELETE","删除尚未发行活动，保留操作证据");
    }
    @Override public CouponDO get(Long id){marketing.requireTemplate(id,false);return safe(mapper.selectById(id));}
    @Override public List<CouponDO> getList(){var result=mapper.selectList(scoped().orderByDesc(CouponDO::getId));result.forEach(this::safe);return result;}
    @Override public PageResult<CouponDO> getPage(CouponPageReqVO request){
        var w=scoped().eqIfPresent(CouponDO::getShopId,request.getShopId()).likeIfPresent(CouponDO::getShopName,request.getShopName()).likeIfPresent(CouponDO::getTitle,request.getTitle()).orderByDesc(CouponDO::getId);
        var result=mapper.selectPage(request,w);result.getList().forEach(this::safe);return result;
    }
    @Override public List<CouponDO> getList(CouponExportReqVO request){
        var result=mapper.selectList(scoped().eqIfPresent(CouponDO::getShopId,request.getShopId()).likeIfPresent(CouponDO::getTitle,request.getTitle()).orderByDesc(CouponDO::getId));result.forEach(this::safe);return result;
    }
}
