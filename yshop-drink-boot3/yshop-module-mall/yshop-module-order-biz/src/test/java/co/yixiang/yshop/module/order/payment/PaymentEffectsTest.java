package co.yixiang.yshop.module.order.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.service.user.MemberUserService;
import co.yixiang.yshop.module.member.service.userbill.UserBillService;
import co.yixiang.yshop.module.message.enums.WechatTempateEnum;
import co.yixiang.yshop.module.message.mq.producer.WeixinNoticeProducer;
import co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO;
import co.yixiang.yshop.module.order.dal.dataobject.storeordercartinfo.StoreOrderCartInfoDO;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.enums.*;
import co.yixiang.yshop.module.order.service.payment.*;
import co.yixiang.yshop.module.order.service.storeorder.AsyncStoreOrderService;
import co.yixiang.yshop.module.order.service.storeordercartinfo.StoreOrderCartInfoService;
import co.yixiang.yshop.module.order.service.storeorderstatus.StoreOrderStatusService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;

/** Real production after-commit policy; statistics/notification IO are explicit test doubles. */
class PaymentEffectsTest {
    MemberUserService users; AsyncStoreOrderService statistics; StoreOrderMapper orders;
    StoreOrderCartInfoService carts; WeixinNoticeProducer notices; PaymentEffects effects;
    PaymentOrder order; MemberUserDO member; StoreOrderDO detail;
    @BeforeEach void setup() {
        users=mock(MemberUserService.class);statistics=mock(AsyncStoreOrderService.class);
        orders=mock(StoreOrderMapper.class);carts=mock(StoreOrderCartInfoService.class);notices=mock(WeixinNoticeProducer.class);
        effects=new PaymentEffects(users,mock(UserBillService.class),mock(StoreOrderStatusService.class),statistics,carts,orders,notices);
        order=new PaymentOrder();order.setUid(101L);order.setId(201L);order.setOrderId("synthetic-order");
        member=new MemberUserDO();member.setId(101L);member.setLoginType(AppFromEnum.ROUNTINE.getValue());
        detail=new StoreOrderDO();detail.setOrderType("takein");detail.setNumberId(7L);detail.setShopName("Synthetic A");
        when(users.getUser(101L)).thenReturn(member);when(orders.selectById(201L)).thenReturn(detail);
        var first=new StoreOrderCartInfoDO();first.setTitle("Tea");var second=new StoreOrderCartInfoDO();second.setTitle("Pearl");
        when(carts.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(first,second));
    }
    @ParameterizedTest @ValueSource(strings={"missing-order","missing-member","desk","other-client"})
    void ineligibleNotificationsDoNotSendOrReadCart(String reason) {
        switch(reason) {
            case "missing-order" -> when(orders.selectById(201L)).thenReturn(null);
            case "missing-member" -> when(users.getUser(101L)).thenReturn(null);
            case "desk" -> detail.setOrderType(OrderLogEnum.ORDER_TAKE_DESK.getValue());
            default -> member.setLoginType("h5");
        }
        effects.afterCommit(order);
        verify(statistics).orderData(101L);verifyNoInteractions(carts,notices);
    }
    @Test void routineNoticeKeepsOrderStoreAndItemSnapshot() {
        effects.afterCommit(order);
        verify(notices).sendNoticeMessage(101L,WechatTempateEnum.PAY_SUCCESS.getValue(),WechatTempateEnum.SUBSCRIBE.getValue(),
            "synthetic-order","","","","",201L,7,"Tea,Pearl","Synthetic A");
    }
    @Test void missingPickupNumberStillSendsEligibleNotice() {
        detail.setNumberId(null);effects.afterCommit(order);
        verify(notices).sendNoticeMessage(101L,WechatTempateEnum.PAY_SUCCESS.getValue(),WechatTempateEnum.SUBSCRIBE.getValue(),
            "synthetic-order","","","","",201L,null,"Tea,Pearl","Synthetic A");
    }
    @Test void failedStatisticsDoesNotSuppressPostCommitNotice() {
        doThrow(new IllegalStateException("synthetic outage")).when(statistics).orderData(101L);
        assertDoesNotThrow(()->effects.afterCommit(order));
        verify(notices).sendNoticeMessage(101L,WechatTempateEnum.PAY_SUCCESS.getValue(),WechatTempateEnum.SUBSCRIBE.getValue(),
            "synthetic-order","","","","",201L,7,"Tea,Pearl","Synthetic A");
    }
}
