package co.yixiang.yshop.module.order.ordering;

import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.dal.mysql.storeordercartinfo.StoreOrderCartInfoMapper;
import co.yixiang.yshop.module.order.service.fulfillment.OrderFulfillmentService;
import co.yixiang.yshop.module.order.service.storeorder.StoreOrderServiceImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoreOrderDetailStatusTest {

    @ParameterizedTest
    @CsvSource({"1,0,退款中", "2,0,已退款", "0,1,已删除"})
    void pickupFulfillmentDoesNotReplaceRefundOrDeletionStatus(int refundStatus, int systemDeleted, String expectedStatus) {
        long orderId = 17L;
        long memberId = 29L;
        var order = StoreOrderDO.builder().id(orderId).uid(memberId).orderType("takein")
                .paid(1).status(0).refundStatus(refundStatus).isSystemDel(systemDeleted)
                .fulfillmentStatus("WAIT_ACCEPT").fulfillmentVersion(1).build();
        var orders = mock(StoreOrderMapper.class);
        when(orders.selectById(orderId)).thenReturn(order);
        var members = mock(MemberUserMapper.class);
        when(members.selectById(memberId)).thenReturn(MemberUserDO.builder().id(memberId).build());
        var cartItems = mock(StoreOrderCartInfoMapper.class);
        when(cartItems.selectList(any())).thenReturn(List.of());
        var fulfillment = mock(OrderFulfillmentService.class);
        when(fulfillment.history(orderId)).thenReturn(List.of());

        var service = new StoreOrderServiceImpl();
        ReflectionTestUtils.setField(service, "storeOrderMapper", orders);
        ReflectionTestUtils.setField(service, "memberUserMapper", members);
        ReflectionTestUtils.setField(service, "storeOrderCartInfoMapper", cartItems);
        ReflectionTestUtils.setField(service, "orderFulfillmentService", fulfillment);

        var response = service.getStoreOrder(orderId);

        assertEquals(expectedStatus, response.getStatusStr());
        assertEquals("WAIT_ACCEPT", response.getFulfillmentStatus());
    }
}
