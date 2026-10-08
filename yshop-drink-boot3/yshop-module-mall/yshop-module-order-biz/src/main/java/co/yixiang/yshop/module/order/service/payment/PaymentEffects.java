package co.yixiang.yshop.module.order.service.payment;

import co.yixiang.yshop.module.member.enums.BillDetailEnum;
import co.yixiang.yshop.module.member.service.user.MemberUserService;
import co.yixiang.yshop.module.member.service.userbill.UserBillService;
import co.yixiang.yshop.module.message.enums.WechatTempateEnum;
import co.yixiang.yshop.module.message.mq.producer.WeixinNoticeProducer;
import co.yixiang.yshop.module.order.dal.dataobject.storeordercartinfo.StoreOrderCartInfoDO;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.enums.*;
import co.yixiang.yshop.module.order.service.storeorder.AsyncStoreOrderService;
import co.yixiang.yshop.module.order.service.storeordercartinfo.StoreOrderCartInfoService;
import co.yixiang.yshop.module.order.service.storeorderstatus.StoreOrderStatusService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@Slf4j
public class PaymentEffects {
    @jakarta.annotation.Resource
    private org.springframework.jdbc.core.JdbcTemplate couponJdbc;
    private final MemberUserService users;
    private final UserBillService bills;
    private final StoreOrderStatusService statuses;
    private final AsyncStoreOrderService statistics;
    private final StoreOrderCartInfoService carts;
    private final StoreOrderMapper orders;
    private final WeixinNoticeProducer notifications;

    public PaymentEffects(
            MemberUserService users,
            UserBillService bills,
            StoreOrderStatusService statuses,
            AsyncStoreOrderService statistics,
            StoreOrderCartInfoService carts,
            StoreOrderMapper orders,
            WeixinNoticeProducer notifications) {
        this.users = users;
        this.bills = bills;
        this.statuses = statuses;
        this.statistics = statistics;
        this.carts = carts;
        this.orders = orders;
        this.notifications = notifications;
    }

    public void apply(PaymentOrder order, String payType) {
        users.incPayCount(order.getUid());
        statuses.create(
                order.getUid(),
                order.getId(),
                OrderLogEnum.PAY_ORDER_SUCCESS.getValue(),
                OrderLogEnum.PAY_ORDER_SUCCESS.getDesc());
        var user = users.getUser(order.getUid());
        if (user == null) throw new IllegalStateException("PAYMENT_MEMBER_NOT_FOUND");
        bills.expendExact(
                user.getId(),
                "购买商品",
                BillDetailEnum.CATEGORY_1.getValue(),
                BillDetailEnum.TYPE_3.getValue(),
                order.getPayPrice(),
                user.getNowMoney(),
                PayTypeEnum.toType(payType).getDesc() + order.getPayPrice() + "元购买商品",
                order.getOrderId());
        new co.yixiang.yshop.module.coupon.service.marketing.CouponLifecycle(couponJdbc)
            .redeem(order.getOrderId(), order.getUid());
    }

    public void afterCommit(PaymentOrder order) {
        try {
            statistics.orderData(order.getUid());
        } catch (RuntimeException failure) {
            log.warn("payment statistics deferred category={}", failure.getClass().getSimpleName());
        }
        var user = users.getUser(order.getUid());
        var details = orders.selectById(order.getId());
        if (details != null
                && user != null
                && !OrderLogEnum.ORDER_TAKE_DESK.getValue().equals(details.getOrderType())
                && AppFromEnum.ROUNTINE.getValue().equals(user.getLoginType())) {
            var lines =
                    carts.list(
                            new LambdaQueryWrapper<StoreOrderCartInfoDO>()
                                    .eq(StoreOrderCartInfoDO::getOid, order.getId()));
            String names =
                    lines.stream()
                            .map(StoreOrderCartInfoDO::getTitle)
                            .collect(Collectors.joining(","));
            notifications.sendNoticeMessage(
                    order.getUid(),
                    WechatTempateEnum.PAY_SUCCESS.getValue(),
                    WechatTempateEnum.SUBSCRIBE.getValue(),
                    order.getOrderId(),
                    "",
                    "",
                    "",
                    "",
                    order.getId(),
                    details.getNumberId() == null ? null : Math.toIntExact(details.getNumberId()),
                    names,
                    details.getShopName());
        }
    }
}
