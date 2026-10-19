package co.yixiang.yshop.module.coupon.service.couponuser;

import co.yixiang.yshop.module.coupon.controller.app.coupon.vo.AppMyCouponVO;
import co.yixiang.yshop.module.coupon.convert.couponuser.CouponUserConvert;
import co.yixiang.yshop.module.coupon.dal.dataobject.couponuser.CouponUserDO;
import co.yixiang.yshop.module.coupon.dal.mysql.couponuser.CouponUserMapper;
import co.yixiang.yshop.module.coupon.service.marketing.CouponMarketingService;
import co.yixiang.yshop.module.coupon.service.marketing.CouponPolicy;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AppCouponUserServiceImpl extends ServiceImpl<CouponUserMapper,CouponUserDO> implements AppCouponUserService {
    @Resource private JdbcTemplate jdbc;
    @Resource private CouponMarketingService marketing;
    @Override public List<AppMyCouponVO> getList(Long uid,Long shopId,int type,int page,int pagesize) {
        if(uid==null || page<1 || pagesize<1 || pagesize>100 || type<0 || type>3 || shopId!=null && shopId<0)throw CouponPolicy.reject("COUPON_CONTEXT_INVALID");
        var wrapper=new co.yixiang.yshop.framework.mybatis.core.query.LambdaQueryWrapperX<CouponUserDO>().eq(CouponUserDO::getUserId,uid);
        if(shopId!=null && shopId>0) wrapper.and(w->w.eq(CouponUserDO::getShopId,"0").or().apply("CONCAT(',',shop_id,',') LIKE {0}","%,"+shopId+",%"));
        // State filtering after deriving evidence. Bound the response, never misclassify status=1.
        var result=new ArrayList<AppMyCouponVO>();int offset=(page-1)*pagesize;
        for(var c:baseMapper.selectList(wrapper.orderByDesc(CouponUserDO::getId))) {
            var row=jdbc.queryForMap("SELECT *, (type+0) AS coupon_type FROM yshop_coupon_user WHERE id=?",c.getId());row.put("type",row.get("coupon_type"));
            String state=marketing.state(row);
            if(type==0 && !Set.of("AVAILABLE","NOT_YET_VALID").contains(state) || type==1 && !Set.of("USED","RESERVED","REVIEW_REQUIRED","INVALID").contains(state) || type==2 && !"EXPIRED".equals(state))continue;
            if(offset-->0)continue;
            var vo=CouponUserConvert.INSTANCE.convertList03(List.of(c)).get(0);vo.setExchangeCode(null);vo.setReservationState(state);vo.setUnavailableReason("AVAILABLE".equals(state)?null:state);result.add(vo);
            if(result.size()==pagesize)break;
        }
        return result;
    }
}
