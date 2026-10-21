package co.yixiang.yshop.module.order.service.storeorder;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import co.yixiang.yshop.framework.common.constant.ShopConstants;
import co.yixiang.yshop.framework.common.enums.OrderInfoEnum;
import co.yixiang.yshop.framework.common.enums.PayIdEnum;
import co.yixiang.yshop.framework.common.enums.ShopCommonEnum;
import co.yixiang.yshop.framework.common.exception.ErrorCode;
import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.member.controller.admin.user.vo.UserRespVO;
import co.yixiang.yshop.module.member.convert.user.UserConvert;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.enums.BillDetailEnum;
import co.yixiang.yshop.module.member.service.user.MemberUserService;
import co.yixiang.yshop.module.member.service.userbill.UserBillService;
import co.yixiang.yshop.module.message.mq.producer.WeixinNoticeProducer;
import co.yixiang.yshop.module.message.redismq.msg.OrderMsg;
import co.yixiang.yshop.module.order.controller.admin.storeorder.vo.*;
import co.yixiang.yshop.module.order.convert.storeorder.StoreOrderConvert;
import co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO;
import co.yixiang.yshop.module.order.dal.dataobject.storeordercartinfo.StoreOrderCartInfoDO;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.dal.mysql.storeordercartinfo.StoreOrderCartInfoMapper;
import co.yixiang.yshop.module.order.enums.OrderLogEnum;
import co.yixiang.yshop.module.order.enums.PayTypeEnum;
import co.yixiang.yshop.module.order.enums.UpdateOrderEnum;
import co.yixiang.yshop.module.order.service.storeorderstatus.StoreOrderStatusService;
import co.yixiang.yshop.module.order.service.fulfillment.OrderFulfillmentService;
import co.yixiang.yshop.module.product.service.storeproduct.AppStoreProductService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.egzosn.pay.common.bean.RefundOrder;
import com.egzosn.pay.common.bean.RefundResult;
import com.egzosn.pay.spring.boot.core.PayServiceManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.module.member.enums.ErrorCodeConstants.USER_NOT_EXISTS;
import static co.yixiang.yshop.module.order.enums.ErrorCodeConstants.*;

/**
 * 订单 Service 实现类
 *
 * @author yshop
 */
@Slf4j
@Service
@Validated
public class StoreOrderServiceImpl implements StoreOrderService {

    @Resource
    private co.yixiang.yshop.module.member.service.wallet.WalletService walletService;
    @jakarta.annotation.Resource
    private co.yixiang.yshop.module.store.service.storeshop.StoreAccessService storeAccess;

    @Resource
    private StoreOrderMapper storeOrderMapper;
    @Resource
    private MemberUserMapper memberUserMapper;
    @Resource
    private StoreOrderCartInfoMapper storeOrderCartInfoMapper;
    @Resource
    private StoreOrderStatusService storeOrderStatusService;
    @Resource
    private AppStoreOrderService appStoreOrderService;
    @Resource
    private OrderFulfillmentService orderFulfillmentService;
    @Resource
    private MemberUserService userService;
    @Resource
    private WeixinNoticeProducer weixinNoticeProducer;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private UserBillService billService;
    @Resource
    private AppStoreProductService appStoreProductService;
    @Resource
    private PayServiceManager manager;

    @Resource
    private AsyncStoreOrderService asyncStoreOrderService;


    @Value("${yshop.demo}")
    private Boolean isDemo;


    @Override
    public Long createStoreOrder(StoreOrderCreateReqVO createReqVO) {
        // 插入
        StoreOrderDO storeOrder = StoreOrderConvert.INSTANCE.convert(createReqVO);
        storeOrderMapper.insert(storeOrder);
        // 返回
        return storeOrder.getId();
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void updateStoreOrder(StoreOrderUpdateReqVO updateReqVO) {
        // A stale admin page is only an ID hint; every write is based on the current locked row.
        StoreOrderDO current = storeOrderMapper.lockAdminOrder(updateReqVO.getId());
        if (current == null) throw exception(STORE_ORDER_NOT_EXISTS);
        StoreOrderDO updateObj = StoreOrderDO.builder().id(current.getId())
                .deliveryName(updateReqVO.getDeliveryName()).deliveryId(updateReqVO.getDeliveryId())
                .deliveryType(updateReqVO.getDeliveryType())
                .remark(updateReqVO.getRemark()).build();
        if (UpdateOrderEnum.ORDER_SEND.getValue().equals(updateReqVO.getUpdateType())) {
            if (!Integer.valueOf(1).equals(current.getPaid())
                    || !Integer.valueOf(0).equals(current.getRefundStatus())
                    || !Integer.valueOf(0).equals(current.getIsSystemDel())) {
                throw exception(ORDER_STATUS_ERROR);
            }

            // Legacy pickup completion goes through the same READY-only transition service.
            if (OrderLogEnum.ORDER_TAKE_IN.getValue().equals(current.getOrderType())) {
                orderFulfillmentService.legacyStaffTake(current.getId());
                return;
            }

            // Delivery may only advance from the initial paid state. The conditional write
            // repeats the predicate so stale or concurrent requests cannot reopen an order.
            if (!OrderLogEnum.isDeliveryOrderType(current.getOrderType())
                    || !OrderInfoEnum.STATUS_0.getValue().equals(current.getStatus())) {
                throw exception(ORDER_STATUS_ERROR);
            }
            String deliveryName = updateReqVO.getDeliveryName() != null ? updateReqVO.getDeliveryName() : current.getDeliveryName();
            String deliveryId = updateReqVO.getDeliveryId() != null ? updateReqVO.getDeliveryId() : current.getDeliveryId();
            String deliveryType = updateReqVO.getDeliveryType() != null ? updateReqVO.getDeliveryType() : current.getDeliveryType();
            String remark = updateReqVO.getRemark() != null ? updateReqVO.getRemark() : current.getRemark();
            if (storeOrderMapper.markDispatched(current.getId(), current.getUid(), deliveryName,
                    deliveryId, deliveryType, remark) != 1) {
                throw exception(ORDER_STATUS_ERROR);
            }
            storeOrderStatusService.create(current.getUid(), current.getId(), OrderLogEnum.DELIVERY_GOODS.getValue(),
                    "已发货 快递公司：" + deliveryName + "快递单号：" + deliveryId);

            // Preserve the existing auto-confirm schedule, but enqueue only after a real transition.
            try {
                RBlockingDeque<Object> blockingDeque = redissonClient.getBlockingDeque(ShopConstants.REDIS_ORDER_OUTTIME_UNCONFIRM);
                RDelayedQueue<Object> delayedQueue = redissonClient.getDelayedQueue(blockingDeque);
                delayedQueue.offer(OrderMsg.builder().orderId(current.getOrderId()).build(), ShopConstants.ORDER_OUTTIME_UNCONFIRM, TimeUnit.MINUTES);
                String s = TimeUnit.SECONDS.toSeconds(ShopConstants.ORDER_OUTTIME_UNCONFIRM) + "分钟";
                log.info("添加延时队列成功 ，延迟时间：" + s);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            return;
        }
        // Ordinary logistics/remark edits never rewrite order state or trigger rewards/queues.
        storeOrderMapper.updateById(updateObj);
    }

    @Override
    public void deleteStoreOrder(Long id) {
        // 校验存在
        validateStoreOrderExists(id);
        // 删除
        StoreOrderDO storeOrderDO = StoreOrderDO.builder()
                .isSystemDel(ShopCommonEnum.DELETE_1.getValue())
                .id(id)
                .build();
        storeOrderMapper.updateById(storeOrderDO);
    }

    @Override
    public void payStoreOrder(Long id) {
        // 校验存在
        validateStoreOrderExists(id);
        StoreOrderDO storeOrderDO = storeOrderMapper.selectById(id);
        //下线支付
        appStoreOrderService.paySuccess(storeOrderDO.getOrderId(),PayTypeEnum.CASH.getValue());
    }

    @Override
    public void takeStoreOrder(Long id) {
        StoreOrderDO storeOrderDO = storeOrderMapper.selectById(id);
        if (storeOrderDO == null) throw exception(STORE_ORDER_NOT_EXISTS);
        if (OrderLogEnum.ORDER_TAKE_IN.getValue().equals(storeOrderDO.getOrderType())) {
            orderFulfillmentService.legacyStaffTake(id);
            return;
        }
        appStoreOrderService.takeOrder(storeOrderDO.getOrderId(),storeOrderDO.getUid());
    }


    private void validateStoreOrderExists(Long id) {
        if (storeOrderMapper.selectById(id) == null) {
            throw exception(STORE_ORDER_NOT_EXISTS);
        }
    }

    @Override
    public StoreOrderRespVO getStoreOrder(Long id) {
        StoreOrderDO storeOrderDO = storeOrderMapper.selectById(id);
        StoreOrderRespVO storeOrderRespVO = StoreOrderConvert.INSTANCE.convert(storeOrderDO);
        MemberUserDO memberUserDO = memberUserMapper.selectById(storeOrderRespVO.getUid());
        UserRespVO  userRespVO = UserConvert.INSTANCE.convert4(memberUserDO);
        storeOrderRespVO.setUserRespVO(userRespVO);

        LambdaQueryWrapper<StoreOrderCartInfoDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StoreOrderCartInfoDO::getOid,storeOrderDO.getId());
        List<StoreOrderCartInfoDO> storeOrderCartInfoDOList = storeOrderCartInfoMapper.selectList(wrapper);
        storeOrderRespVO.setStoreOrderCartInfoDOList(storeOrderCartInfoDOList);

        storeOrderRespVO.setStatusStr(this.handleOrderStatus(storeOrderRespVO.getPaid()
                ,storeOrderRespVO.getStatus(),storeOrderRespVO.getRefundStatus(),storeOrderRespVO.getIsSystemDel()));
        if (OrderLogEnum.ORDER_TAKE_IN.getValue().equals(storeOrderDO.getOrderType())
                && storeOrderDO.getFulfillmentStatus() != null
                && !"LEGACY".equals(storeOrderDO.getFulfillmentStatus())) {
            storeOrderRespVO.setStatusStr(switch (storeOrderDO.getFulfillmentStatus()) {
                case "WAIT_ACCEPT" -> "待接单";
                case "ACCEPTED" -> "已接单";
                case "PREPARING" -> "制作中";
                case "READY" -> "待取餐";
                case "COLLECTED" -> "已取餐";
                default -> "履约状态待核对";
            });
        }
        storeOrderRespVO.setFulfillmentTimeline(orderFulfillmentService.history(id));
        return storeOrderRespVO;
    }

    @Override
    public List<StoreOrderDO> getStoreOrderList(Collection<Long> ids) {
        return storeOrderMapper.selectBatchIds(ids);
    }

    /**
     * 订单查询
     * @param pageReqVO 分页查询
     * @return
     */
    @Override
    public PageResult<StoreOrderRespVO> getStoreOrderPage(StoreOrderPageReqVO pageReqVO) {
        PageResult<StoreOrderDO> pageResult =  storeOrderMapper.selectPage(pageReqVO,storeAccess.allowedShopIds());
        PageResult<StoreOrderRespVO> storeOrderRespVO =  StoreOrderConvert.INSTANCE.convertPage(pageResult);
        for (StoreOrderRespVO storeOrderRespVO1 : storeOrderRespVO.getList()) {
            LambdaQueryWrapper<StoreOrderCartInfoDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(StoreOrderCartInfoDO::getOid,storeOrderRespVO1.getId());
            List<StoreOrderCartInfoDO> storeOrderCartInfoDOList = storeOrderCartInfoMapper.selectList(wrapper);
            MemberUserDO memberUserDO = memberUserMapper.selectById(storeOrderRespVO1.getUid());
            UserRespVO  userRespVO = UserConvert.INSTANCE.convert4(memberUserDO);
            storeOrderRespVO1.setStoreOrderCartInfoDOList(storeOrderCartInfoDOList);
            storeOrderRespVO1.setUserRespVO(userRespVO);
            storeOrderRespVO1.setStatusStr(this.handleOrderStatus(storeOrderRespVO1.getPaid()
                    ,storeOrderRespVO1.getStatus(),storeOrderRespVO1.getRefundStatus(),storeOrderRespVO1.getIsSystemDel()));
        }
        return storeOrderRespVO;
    }

    @Override
    public List<StoreOrderDO> getStoreOrderList(StoreOrderExportReqVO exportReqVO) {
        return storeOrderMapper.selectList(exportReqVO,storeAccess.allowedShopIds());
    }

    /**
     * 确认订单退款
     *
     * @param id 单号
     * @param price   金额
     * @param type    ShopCommonEnum
     * @param salesId 售后id
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void orderRefund(Long id, BigDecimal price, Integer type, Long salesId) {

        StoreOrderDO storeOrderDO = storeOrderMapper.selectById(id);
        if (storeOrderDO == null) {
            throw exception(STORE_ORDER_NOT_EXISTS);
        }

        boolean balanceRefund = PayTypeEnum.YUE.getValue().equals(storeOrderDO.getPayType());
        if (balanceRefund) {
            // Only balance refunds lock the order, in the same order -> user sequence as yuePay.
            // Validate the current row, not the earlier read; never reroute to a provider while locked.
            storeOrderDO = storeOrderMapper.selectOne(
                    new LambdaQueryWrapper<StoreOrderDO>().eq(StoreOrderDO::getId, id).last("FOR UPDATE"));
            if (storeOrderDO == null) throw exception(STORE_ORDER_NOT_EXISTS);
            if (!PayTypeEnum.YUE.getValue().equals(storeOrderDO.getPayType())) {
                throw exception(ORDER_REFUND_NOT);
            }
            if (!OrderInfoEnum.PAY_STATUS_1.getValue().equals(storeOrderDO.getPaid())
                    || Boolean.TRUE.equals(storeOrderDO.getDeleted())
                    || !Integer.valueOf(0).equals(storeOrderDO.getIsSystemDel())) {
                throw exception(ORDER_REFUND_NOT);
            }
            if (OrderInfoEnum.REFUND_STATUS_2.getValue().equals(storeOrderDO.getRefundStatus())) {
                throw exception(ORDER_REFUNDED);
            }
            Integer status = storeOrderDO.getStatus();
            boolean applying = OrderInfoEnum.STATUS_NE1.getValue().equals(status)
                    && OrderInfoEnum.REFUND_STATUS_1.getValue().equals(storeOrderDO.getRefundStatus());
            if ((!Integer.valueOf(0).equals(storeOrderDO.getRefundStatus())
                    && !OrderInfoEnum.REFUND_STATUS_1.getValue().equals(storeOrderDO.getRefundStatus()))
                    || (status == null || (!applying && (status < 0 || status > 3)))) {
                throw exception(ORDER_REFUND_NOT);
            }
            if (price == null || price.signum() <= 0 || storeOrderDO.getPayPrice() == null
                    || price.compareTo(storeOrderDO.getPayPrice()) > 0) {
                throw exception(ORDER_PRICE_ERROR);
            }
        }

        MemberUserDO userQueryVo = userService.getById(storeOrderDO.getUid());
        if (ObjectUtil.isNull(userQueryVo)) {
            throw exception(USER_NOT_EXISTS);
        }

        if (OrderInfoEnum.REFUND_STATUS_2.getValue().equals(storeOrderDO.getRefundStatus())) {
            throw exception(ORDER_REFUNDED);
        }

        if (storeOrderDO.getPayPrice().compareTo(price) < 0) {
            throw exception(ORDER_PRICE_ERROR);
        }

        storeOrderDO.setRefundStatus(OrderInfoEnum.REFUND_STATUS_2.getValue());
        storeOrderDO.setRefundPrice(price);


        //生成分布式唯一值用于退款订单
        String orderSn = IdUtil.getSnowflake(0, 0).nextIdStr();
        BigDecimal balance = userQueryVo.getNowMoney();
        //根据支付类型不同退款不同
        if (PayTypeEnum.YUE.getValue().equals(storeOrderDO.getPayType())) {
            //退款到余额
            var movement = walletService.credit(storeOrderDO.getUid(), price,
                    co.yixiang.yshop.module.member.service.wallet.WalletType.ORDER_REFUND,
                    storeOrderDO.getOrderId(), "refund:" + storeOrderDO.getOrderId());
            if (!movement.first()) throw exception(ORDER_REFUNDED);
            balance = movement.transaction().getBalanceAfter();

        } else if (PayTypeEnum.WEIXIN.getValue().equals(storeOrderDO.getPayType())) {
            if(isDemo){
                throw exception(new ErrorCode(888888,"演示模式没有配置证书无法微信退款的哦！"));
            }
            log.error("{},{},{},{}",orderSn,storeOrderDO.getOrderId(),price,storeOrderDO.getPayPrice());
            RefundOrder refundOrder = new RefundOrder(orderSn,"",storeOrderDO.getOrderId(),price,storeOrderDO.getPayPrice());
            //查询微信退款
            if(StrUtil.isNotEmpty(storeOrderDO.getOutTradeNo())){
                RefundOrder refundQueryOrder = new RefundOrder();
                refundQueryOrder.setRefundNo(storeOrderDO.getOutTradeNo());
                Map<String, Object> objectMap =  manager.refundQuery(PayIdEnum.WX_MINIAPP.getValue(), refundQueryOrder);
                if(objectMap.get("'return_code'").toString().equals("SUCCESS")){
                    storeOrderMapper.updateById(storeOrderDO);
                    return;
                }
            }

            RefundResult refundResult = manager.refund(PayIdEnum.WX_MINIAPP.getValue(), refundOrder);
            if(refundResult.getCode().equals("FAIL")){
                log.error("支付退款错误：{}",refundResult.getMsg());
                throw exception(new ErrorCode(999999,refundResult.getMsg()));
            }else {
                if(refundResult.getResultCode().equals("FAIL")){
                    log.error("支付退款错误：{}",refundResult.getResultMsg());
                    throw exception(new ErrorCode(999998,refundResult.getResultMsg()));
                }
            }
            storeOrderDO.setOutTradeNo(orderSn);
        }else if (PayTypeEnum.ALI.getValue().equals(storeOrderDO.getPayType())){
            throw exception(new ErrorCode(999997,"支付宝暂时不支持退款"));
        }
        int updated = storeOrderMapper.updateById(storeOrderDO);
        if (balanceRefund && updated != 1) throw exception(ORDER_STATUS_ERROR);
        //增加流水
        billService.income(storeOrderDO.getUid(), "商品退款", BillDetailEnum.CATEGORY_1.getValue(),
                BillDetailEnum.TYPE_5.getValue(),
                price.doubleValue(),
                balance.doubleValue(),
                "订单退款到余额" + price + "元", storeOrderDO.getId().toString(),orderSn);
        storeOrderStatusService.create(storeOrderDO.getUid(), storeOrderDO.getId(),
                OrderLogEnum.REFUND_ORDER_SUCCESS.getValue(), "退款给用户：" + price + "元");

        //退库存
        this.regressionStock(storeOrderDO,0);

    }

    /**
     * 订单30s内通知
     * @return
     */
    @Override
    public Long orderNotice() {
        LambdaQueryWrapper<StoreOrderDO> wrapper = new LambdaQueryWrapper();
        Long shopId = SecurityFrameworkUtils.getLoginUser().getShopId();
        if(shopId > 0) {
            wrapper.eq(StoreOrderDO::getShopId,shopId);
        }
        wrapper.eq(StoreOrderDO::getPaid, OrderInfoEnum.PAY_STATUS_1.getValue());
        LocalDateTime nowTime = LocalDateTime.now();
        wrapper.ge(StoreOrderDO::getCreateTime,nowTime.minusSeconds(30));

        return  storeOrderMapper.selectCount(wrapper);

    }

    /**
     * 退回库存
     *
     * @param order 订单
     */
    private void regressionStock(StoreOrderDO order, Integer type) {

        LambdaQueryWrapper<StoreOrderCartInfoDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StoreOrderCartInfoDO::getOid, order.getId());

        List<StoreOrderCartInfoDO> cartInfoList = storeOrderCartInfoMapper.selectList(wrapper);
        for (StoreOrderCartInfoDO cartInfo : cartInfoList) {
            appStoreProductService.incProductStock(cartInfo.getNumber(), cartInfo.getProductId(), cartInfo.getSpec(),
                    0L, null);
        }
    }


    /**
     * 处理订单状态
     * @param payStatus
     * @param status
     * @param refundStatus
     * @param del
     * @return
     */
    private String handleOrderStatus(Integer payStatus,Integer status,Integer refundStatus,Integer del) {
        String statusName = "";
        if (del == 1){
            statusName = "已删除";
        }else if (payStatus == 0 && status == 0) {
            statusName = "未支付";
        } else if (payStatus == 1 && status == 0 && refundStatus == 0) {
            statusName = "未发货";
        }  else if (payStatus == 1 && status == 1 && refundStatus == 0) {
            statusName = "待收货";
        }  else if (payStatus == 1 && status == 2 && refundStatus == 0) {
            statusName = "待评价";
        } else if (payStatus == 1 && status == 3 && refundStatus == 0) {
            statusName = "已完成";
        } else if (payStatus == 1 && refundStatus == 2) {
            statusName = "已退款";
        }
        else if (payStatus == 1 && refundStatus == 1) {
            statusName = "退款中";
        }
        return statusName;
    }




}
