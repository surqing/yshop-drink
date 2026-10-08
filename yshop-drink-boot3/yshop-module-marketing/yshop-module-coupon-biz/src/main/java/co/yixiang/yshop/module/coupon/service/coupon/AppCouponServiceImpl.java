package co.yixiang.yshop.module.coupon.service.coupon;

import co.yixiang.yshop.module.coupon.controller.app.coupon.vo.AppCouponVO;
import co.yixiang.yshop.module.coupon.convert.coupon.CouponConvert;
import co.yixiang.yshop.module.coupon.dal.dataobject.coupon.CouponDO;
import co.yixiang.yshop.module.coupon.dal.mysql.coupon.CouponMapper;
import co.yixiang.yshop.module.coupon.service.marketing.CouponMarketingService;
import co.yixiang.yshop.module.coupon.service.marketing.CouponPolicy;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AppCouponServiceImpl extends ServiceImpl<CouponMapper,CouponDO> implements AppCouponService {
    @Resource private CouponMarketingService marketing;
    @Resource private JdbcTemplate jdbc;
    @Override public List<AppCouponVO> getNotList(Long uid,Long shopId,int page,int pagesize) {
        if(uid==null || page<1 || pagesize<1 || pagesize>100 || shopId!=null && shopId<0) throw CouponPolicy.reject("COUPON_CONTEXT_INVALID");
        var wrapper=new co.yixiang.yshop.framework.mybatis.core.query.LambdaQueryWrapperX<CouponDO>();
        wrapper.eq(CouponDO::getClaimMode,"PUBLIC").eq(CouponDO::getIsSwitch,1);
        if(shopId!=null && shopId>0) wrapper.and(w->w.eq(CouponDO::getShopId,"0").or().apply("CONCAT(',',shop_id,',') LIKE {0}","%,"+shopId+",%"));
        var result=new ArrayList<AppCouponVO>();
        var list=baseMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CouponDO>(page,pagesize),wrapper.orderByDesc(CouponDO::getWeigh).orderByDesc(CouponDO::getId));
        for(var c:list.getRecords()) {
            var row=jdbc.queryForMap("SELECT *, (type+0) AS coupon_type FROM yshop_coupon WHERE id=?",c.getId());row.put("type",row.get("coupon_type"));
            var vo=CouponConvert.INSTANCE.convert01(c);vo.setExchangeCode(null);
            String reason=marketing.claimReason(row,uid);vo.setClaimReason(reason);vo.setIsReceive("AVAILABLE".equals(reason)?0:1);
            vo.setClaimedCount(jdbc.queryForObject("SELECT COUNT(*) FROM yshop_coupon_user WHERE user_id=? AND coupon_id=?",Long.class,uid,c.getId()));
            result.add(vo);
        }
        return result;
    }
    @Override public void receive(Long uid,Long id,String code) {
        // Compatible legacy retry semantics: a single deterministic request, never unlimited retries.
        marketing.claim(uid,id,code,"legacy_"+CouponMarketingService.digest(id==null?code:id.toString()).substring(0,32));
    }
    @Override public void receive(Long uid,Long id,String code,String key) {
        marketing.claim(uid,id,code,key);
    }
}
