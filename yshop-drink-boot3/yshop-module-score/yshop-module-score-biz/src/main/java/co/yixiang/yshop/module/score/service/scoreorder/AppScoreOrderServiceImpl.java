package co.yixiang.yshop.module.score.service.scoreorder;

import cn.hutool.core.util.IdUtil;
import co.yixiang.yshop.framework.common.enums.OrderInfoEnum;
import co.yixiang.yshop.framework.common.enums.ShopCommonEnum;
import co.yixiang.yshop.framework.common.util.servlet.ServletUtils;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import java.math.BigDecimal;
import co.yixiang.yshop.module.member.dal.dataobject.useraddress.UserAddressDO;
import co.yixiang.yshop.module.member.enums.BillDetailEnum;
import co.yixiang.yshop.module.member.service.useraddress.AppUserAddressService;
import co.yixiang.yshop.module.member.service.userbill.UserBillService;
import co.yixiang.yshop.module.score.controller.app.order.param.AppScoreOrderParam;
import co.yixiang.yshop.module.score.controller.app.order.vo.AppScoreOrderVO;
import co.yixiang.yshop.module.score.convert.scoreorder.ScoreOrderConvert;
import co.yixiang.yshop.module.score.dal.dataobject.scoreorder.ScoreOrderDO;
import co.yixiang.yshop.module.score.dal.dataobject.scoreproduct.ScoreProductDO;
import co.yixiang.yshop.module.score.dal.mysql.scoreorder.ScoreOrderMapper;
import co.yixiang.yshop.module.score.dal.mysql.scoreproduct.ScoreProductMapper;
import co.yixiang.yshop.module.score.enums.OrderStatusEnum;
import co.yixiang.yshop.module.score.service.scoreproduct.AppScoreProductService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;

import java.util.List;
import java.util.stream.Collectors;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.module.member.enums.ErrorCodeConstants.USER_NOT_EXISTS;
import static co.yixiang.yshop.module.score.enums.ErrorCodeConstants.*;

/**
 * 积分商城订单 Service 实现类
 *
 * @author yshop
 */
@Service
@Validated
public class AppScoreOrderServiceImpl extends ServiceImpl<ScoreOrderMapper, ScoreOrderDO> implements AppScoreOrderService {

    @Resource
    private ScoreOrderMapper orderMapper;
    @Resource
    private ScoreProductMapper scoreProductMapper;
    @Resource
    private AppScoreProductService appScoreProductService;
    @Resource
    private MemberUserMapper memberUserMapper;
    @Resource
    private UserBillService billService;
    @Resource
    private  AppUserAddressService appUserAddressService;



    /**
     * 提交
     * @param uid
     * @param appScoreOrderParam
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void submit(Long uid,AppScoreOrderParam appScoreOrderParam) {
        if (uid == null) throw exception(USER_NOT_EXISTS);
        if (appScoreOrderParam == null) throw exception(ORDER_PARAM_INVALID);
        int quantity = positiveInteger(appScoreOrderParam.getNum());
        long productId = positiveProductId(appScoreOrderParam.getProductId());
        // Lock product -> member, matching all integral writers' member row lock.
        ScoreProductDO scoreProductDO = scoreProductMapper.lockById(productId);
        if (scoreProductDO == null || !Integer.valueOf(1).equals(scoreProductDO.getIsSwitch())
                || scoreProductDO.getScore() == null || scoreProductDO.getScore() <= 0) {
            throw exception(PRODUCT_NOT_EXISTS);
        }
        if (scoreProductDO.getStock() == null || scoreProductDO.getStock() < quantity) {
            throw exception(PRODUCT_NOT_STOCK);
        }
        final int totalScore;
        try {
            totalScore = Math.multiplyExact(scoreProductDO.getScore(), quantity);
        } catch (ArithmeticException overflow) {
            throw exception(ORDER_PARAM_INVALID);
        }
        MemberUserDO memberUserDO = memberUserMapper.lockById(uid);
        if (memberUserDO == null) throw exception(USER_NOT_EXISTS);
        UserAddressDO userAddressDO = appUserAddressService.getOwnedAddress(uid, appScoreOrderParam.getAddressId());
        BigDecimal cost = BigDecimal.valueOf(totalScore);
        BigDecimal before = memberUserDO.getIntegral();
        if (before == null || before.compareTo(cost) < 0) throw exception(SCORE_NOT);
        BigDecimal after = before.subtract(cost);

        //生成分布式唯一值
        String orderSn = IdUtil.getSnowflake(0, 0).nextIdStr();
        ScoreOrderDO scoreOrderDO = ScoreOrderDO.builder()
                .havePaid(ShopCommonEnum.IS_STATUS_1.getValue())
                .customerAddress(userAddressDO.getAddress() + userAddressDO.getDetail())
                .customerName(userAddressDO.getRealName())
                .customerPhone(userAddressDO.getPhone())
                .ip(ServletUtils.getClientIP())
                .orderId(orderSn)
                .productId(scoreProductDO.getId())
                .number(quantity)
                .score(scoreProductDO.getScore())
                .totalScore(totalScore)
                .uid(uid)
                .build();
        if (orderMapper.insert(scoreOrderDO) != 1) throw new IllegalStateException("SCORE_ORDER_INSERT_FAILED");
        if (scoreProductMapper.decStockIncSales(quantity, productId) != 1)
            throw new IllegalStateException("SCORE_STOCK_UPDATE_FAILED");
        if (memberUserMapper.changeIntegral(uid, before, after) != 1)
            throw new IllegalStateException("SCORE_INTEGRAL_UPDATE_FAILED");
        billService.expendExact(uid, "积分兑换", BillDetailEnum.CATEGORY_2.getValue(),
                BillDetailEnum.TYPE_3.getValue(), cost, after, totalScore + "积分兑换商品", orderSn);
    }

    private int positiveInteger(String number) {
        if (number == null || !number.matches("[0-9]+")) throw exception(ORDER_PARAM_INVALID);
        try {
            int value = Integer.parseInt(number);
            if (value <= 0) throw exception(ORDER_PARAM_INVALID);
            return value;
        } catch (NumberFormatException overflow) { throw exception(ORDER_PARAM_INVALID); }
    }

    private long positiveProductId(String productId) {
        if (productId == null || !productId.matches("[0-9]+")) throw exception(ORDER_PARAM_INVALID);
        try {
            long value = Long.parseLong(productId);
            if (value <= 0) throw exception(ORDER_PARAM_INVALID);
            return value;
        } catch (NumberFormatException overflow) { throw exception(ORDER_PARAM_INVALID); }
    }


    /**
     * 订单列表
     *
     * @param uid   用户id
     * @param type  OrderStatusEnum
     * @param page  page
     * @param limit limit
     * @return list
     */
    @Override
    public List<AppScoreOrderVO> orderList(Long uid, int type, int page, int limit) {
        LambdaQueryWrapper<ScoreOrderDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScoreOrderDO::getUid, uid);
        wrapper.orderByDesc(ScoreOrderDO::getId);
        switch (OrderStatusEnum.toType(type)) {
            case STATUS__1:
                break;
            //待发货
            case STATUS_0:
                wrapper.eq(ScoreOrderDO::getHavePaid, OrderInfoEnum.PAY_STATUS_1.getValue())
                        .eq(ScoreOrderDO::getHaveDelivered, OrderInfoEnum.STATUS_0.getValue())
                        .eq(ScoreOrderDO::getHaveReceived, OrderInfoEnum.STATUS_0.getValue());
                break;
            //待收货
            case STATUS_1:
                wrapper.eq(ScoreOrderDO::getHavePaid, OrderInfoEnum.PAY_STATUS_1.getValue())
                        .eq(ScoreOrderDO::getHaveDelivered, OrderInfoEnum.STATUS_1.getValue())
                        .eq(ScoreOrderDO::getHaveReceived, OrderInfoEnum.STATUS_0.getValue());
                break;
            //已完成
            case STATUS_2:
                wrapper.eq(ScoreOrderDO::getHavePaid, OrderInfoEnum.PAY_STATUS_1.getValue())
                        //.eq(ScoreOrderDO::getHaveDelivered, OrderInfoEnum.STATUS_1.getValue())
                        .eq(ScoreOrderDO::getHaveReceived, OrderInfoEnum.STATUS_1.getValue());
                break;
            default:
        }

        Page<ScoreOrderDO> pageModel = new Page<>(page, limit);
        IPage<ScoreOrderDO> pageList = orderMapper.selectPage(pageModel, wrapper);
        List<AppScoreOrderVO> list = ScoreOrderConvert.INSTANCE.convertList01(pageList.getRecords());

        return list.stream().map(this::handleOrder).collect(Collectors.toList());
    }

    /**
     * 订单详情
     * @param uid 用户id
     * @param id
     */
    @Override
    public AppScoreOrderVO orderDetail(Long uid, Long id) {
        LambdaQueryWrapper<ScoreOrderDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScoreOrderDO::getUid, uid).eq(ScoreOrderDO::getId,id);
        ScoreOrderDO scoreOrderDO = this.getOne(wrapper);

        AppScoreOrderVO appScoreOrderVO = ScoreOrderConvert.INSTANCE.convert01(scoreOrderDO);
        return this.handleOrder(appScoreOrderVO);
    }

    /**
     * 收货
     * @param uid
     * @param id
     */
    @Override
    public void take(Long uid, Long id) {
        LambdaQueryWrapper<ScoreOrderDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScoreOrderDO::getUid, uid).eq(ScoreOrderDO::getId,id);
        ScoreOrderDO scoreOrderDO = this.getOne(wrapper);
        if(scoreOrderDO == null){
            throw exception(ORDER_NOT_EXISTS);
        }
        scoreOrderDO.setHaveReceived(ShopCommonEnum.DEFAULT_1.getValue());
        this.updateById(scoreOrderDO);
    }

    /**
     * 处理订单
     * @param order
     * @return
     */
    private AppScoreOrderVO handleOrder(AppScoreOrderVO order) {
        order.setProduct(appScoreProductService.getDetail(order.getProductId()));
        if (OrderInfoEnum.PAY_STATUS_1.getValue().equals(order.getHavePaid())
                && OrderInfoEnum.STATUS_0.getValue().equals(order.getHaveDelivered())
                && OrderInfoEnum.STATUS_0.getValue().equals(order.getHaveReceived())) {
            order.setStatusText("待发货");
        } else if (OrderInfoEnum.PAY_STATUS_1.getValue().equals(order.getHavePaid())
                && OrderInfoEnum.STATUS_1.getValue().equals(order.getHaveDelivered())
                && OrderInfoEnum.STATUS_0.getValue().equals(order.getHaveReceived())) {
            order.setStatusText("待收货");
        } else if (OrderInfoEnum.PAY_STATUS_1.getValue().equals(order.getHavePaid())
                && OrderInfoEnum.STATUS_1.getValue().equals(order.getHaveReceived())) {
            order.setStatusText("已完成");
        }
        return order;
    }



}
