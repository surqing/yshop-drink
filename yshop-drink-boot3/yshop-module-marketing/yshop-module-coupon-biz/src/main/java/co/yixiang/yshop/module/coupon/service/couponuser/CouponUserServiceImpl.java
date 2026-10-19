package co.yixiang.yshop.module.coupon.service.couponuser;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.mybatis.core.query.LambdaQueryWrapperX;
import co.yixiang.yshop.module.coupon.controller.admin.couponuser.vo.*;
import co.yixiang.yshop.module.coupon.dal.dataobject.couponuser.CouponUserDO;
import co.yixiang.yshop.module.coupon.dal.mysql.couponuser.CouponUserMapper;
import co.yixiang.yshop.module.coupon.service.marketing.*;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CouponUserServiceImpl implements CouponUserService {
    @Resource private CouponUserMapper mapper;
    @Resource private CouponMarketingService marketing;
    @Resource private JdbcTemplate jdbc;
    private LambdaQueryWrapperX<CouponUserDO> scoped(){
        var w=new LambdaQueryWrapperX<CouponUserDO>();String scope=marketing.adminScopeRegex();if(scope!=null)w.apply("shop_id REGEXP {0}",scope);return w;
    }
    private CouponUserDO safe(CouponUserDO c){
        if(c!=null){c.setExchangeCode(null);var row=jdbc.queryForMap("SELECT *, (type+0) AS coupon_type FROM yshop_coupon_user WHERE id=?",c.getId());row.put("type",row.get("coupon_type"));c.setReservationState(marketing.state(row));}return c;
    }
    // An issued right is not an editable CRUD record. Keep legacy routes fail closed.
    @Override public Integer createUser(CouponUserCreateReqVO request){throw CouponPolicy.reject("COUPON_RAW_ISSUANCE_DISABLED");}
    @Override public void updateUser(CouponUserUpdateReqVO request){throw CouponPolicy.reject("COUPON_ISSUED_RIGHT_IMMUTABLE");}
    @Override public void deleteUser(Integer id){throw CouponPolicy.reject("COUPON_USE_EXPLICIT_INVALIDATION");}
    @Override public CouponUserDO getUser(Integer id){var c=mapper.selectById(id);if(c==null)throw CouponPolicy.reject("COUPON_NOT_EXISTS");marketing.requireScope(c.getShopId());return safe(c);}
    @Override public List<CouponUserDO> getUserList(Integer couponId){marketing.requireTemplate(couponId,false);var list=mapper.selectList(scoped().eq(CouponUserDO::getCouponId,couponId).orderByDesc(CouponUserDO::getId));list.forEach(this::safe);return list;}
    @Override public PageResult<CouponUserDO> getUserPage(CouponUserPageReqVO r){var result=mapper.selectPage(r,scoped().eqIfPresent(CouponUserDO::getCouponId,r.getCouponId()).eqIfPresent(CouponUserDO::getUserId,r.getUserId()).orderByDesc(CouponUserDO::getId));result.getList().forEach(this::safe);return result;}
    @Override public List<CouponUserDO> getUserList(CouponUserExportReqVO r){var result=mapper.selectList(scoped().eqIfPresent(CouponUserDO::getCouponId,r.getCouponId()).eqIfPresent(CouponUserDO::getUserId,r.getUserId()).orderByDesc(CouponUserDO::getId));result.forEach(this::safe);return result;}
}
