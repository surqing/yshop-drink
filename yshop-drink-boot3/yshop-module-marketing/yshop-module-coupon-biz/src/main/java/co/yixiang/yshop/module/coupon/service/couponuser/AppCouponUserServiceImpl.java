package co.yixiang.yshop.module.coupon.service.couponuser;

import co.yixiang.yshop.framework.common.enums.ShopCommonEnum;
import co.yixiang.yshop.framework.mybatis.core.query.LambdaQueryWrapperX;
import co.yixiang.yshop.module.coupon.controller.app.coupon.vo.AppMyCouponVO;
import co.yixiang.yshop.module.coupon.convert.couponuser.CouponUserConvert;
import co.yixiang.yshop.module.coupon.dal.dataobject.couponuser.CouponUserDO;
import co.yixiang.yshop.module.coupon.dal.mysql.couponuser.CouponUserMapper;
import co.yixiang.yshop.module.coupon.enums.CouponStatusEnum;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户领的优惠券 Service 实现类
 *
 * @author yshop
 */
@Service
@Validated
public class AppCouponUserServiceImpl extends ServiceImpl<CouponUserMapper, CouponUserDO> implements AppCouponUserService {

    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Resource
    private CouponUserMapper userMapper;



    /**
     * 获取我的优惠券列表
     * @param shopId 店铺id
     * @param page
     * @param pagesize
     * @return
     */
    @Override
    public List<AppMyCouponVO> getList(Long uid, Long shopId, int type, int page, int pagesize) {
        LocalDateTime nowTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        Page<CouponUserDO> pageModel = new Page<>(page, pagesize);
        LambdaQueryWrapperX<CouponUserDO> wrapper = new LambdaQueryWrapperX<>();
        switch (CouponStatusEnum.toType(type)) {
            case STATUS_0:
                wrapper.eq(CouponUserDO::getStatus,CouponStatusEnum.STATUS_0.getValue())
                        .le(CouponUserDO::getStartTime,nowTime)
                        .gt(CouponUserDO::getEndTime,nowTime);
                break;
            case STATUS_1:
                wrapper.eq(CouponUserDO::getStatus,CouponStatusEnum.STATUS_1.getValue())
                        .le(CouponUserDO::getStartTime,nowTime)
                        .gt(CouponUserDO::getEndTime,nowTime);
                break;
            case STATUS_2:
                wrapper.lt(CouponUserDO::getEndTime,nowTime);
                break;
            default:
                log.warn("为了遵循阿里巴巴规范");
        }
        if(shopId!=null && shopId>0) wrapper.and(w -> w.eq(CouponUserDO::getShopId,"0")
                .or().apply("CONCAT(',',shop_id,',') LIKE {0}","%,"+shopId+",%"));
        wrapper.eq(CouponUserDO::getUserId,uid);
        IPage<CouponUserDO> pageList = this.baseMapper.selectPage(pageModel, wrapper);
        List<AppMyCouponVO> result=CouponUserConvert.INSTANCE.convertList03(pageList.getRecords());
        for(int i=0;i<result.size();i++) {
            var c=pageList.getRecords().get(i); String state;
            if(c.getEndTime()!=null && !c.getEndTime().isAfter(nowTime)) state="EXPIRED";
            else if(c.getStartTime()!=null && c.getStartTime().isAfter(nowTime)) state="NOT_YET_VALID";
            else if(Integer.valueOf(0).equals(c.getStatus()) && c.getReservedOrderId()==null) state="AVAILABLE";
            else if(c.getReservedOrderId()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM yshop_store_order WHERE order_id=? AND uid=? AND paid=0 AND deleted=0",Long.class,c.getReservedOrderId(),uid)>0) state="RESERVED";
            else state="USED";
            result.get(i).setReservationState(state);
        }
        return result;
    }


}
