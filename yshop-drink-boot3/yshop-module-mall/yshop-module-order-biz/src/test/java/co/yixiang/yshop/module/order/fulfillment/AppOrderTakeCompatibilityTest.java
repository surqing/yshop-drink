package co.yixiang.yshop.module.order.fulfillment;

import static org.mockito.Mockito.*;

import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.module.order.controller.app.order.AppOrderController;
import co.yixiang.yshop.module.order.controller.app.order.param.AppDoOrderParam;
import co.yixiang.yshop.module.order.dal.redis.order.AsyncOrderRedisDAO;
import co.yixiang.yshop.module.order.service.fulfillment.OrderFulfillmentService;
import co.yixiang.yshop.module.order.service.ordering.OrderPlacementService;
import co.yixiang.yshop.module.order.service.payment.VerifiedPaymentCallback;
import co.yixiang.yshop.module.order.service.storeorder.AppStoreOrderService;
import co.yixiang.yshop.module.store.service.storeshop.AppStoreShopService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AppOrderTakeCompatibilityTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void oldPickupClientUsesGuardedLegacyTransition() {
        AppStoreOrderService orders = mock(AppStoreOrderService.class);
        AppOrderController controller = controller(orders);
        authenticateMember(17L);
        AppDoOrderParam request = new AppDoOrderParam();
        request.setUni("pickup-1001");

        controller.orderTake(request);

        verify(orders).takeOrder("pickup-1001", 17L);
        verifyNoMoreInteractions(orders);
    }

    @Test
    void versionedPickupRequestUsesVersionedTransition() {
        AppStoreOrderService orders = mock(AppStoreOrderService.class);
        AppOrderController controller = controller(orders);
        authenticateMember(18L);
        AppDoOrderParam request = new AppDoOrderParam();
        request.setUni("pickup-1002");
        request.setExpectedVersion(4);
        request.setRequestKey("member-collect-1002");

        controller.orderTake(request);

        verify(orders).takeOrder("pickup-1002", 18L, 4, "member-collect-1002");
        verifyNoMoreInteractions(orders);
    }

    private AppOrderController controller(AppStoreOrderService orders) {
        return new AppOrderController(
                orders,
                mock(OrderPlacementService.class),
                mock(AsyncOrderRedisDAO.class),
                mock(VerifiedPaymentCallback.class),
                mock(AppStoreShopService.class),
                mock(OrderFulfillmentService.class));
    }

    private void authenticateMember(long uid) {
        LoginUser user = new LoginUser();
        user.setId(uid);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
