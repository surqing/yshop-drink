package co.yixiang.yshop.module.score.order;

import co.yixiang.yshop.module.score.controller.admin.scoreorder.vo.ScoreOrderUpdateReqVO;
import co.yixiang.yshop.module.score.dal.dataobject.scoreorder.ScoreOrderDO;
import co.yixiang.yshop.module.score.dal.mysql.scoreorder.ScoreOrderMapper;
import co.yixiang.yshop.module.score.service.scoreorder.AppScoreOrderServiceImpl;
import co.yixiang.yshop.module.score.service.scoreorder.ScoreOrderServiceImpl;
import co.yixiang.yshop.module.score.controller.admin.scoreorder.ScoreOrderController;
import co.yixiang.yshop.module.score.controller.app.order.AppScoreOrderController;
import co.yixiang.yshop.module.score.controller.common.vo.ScoreOrderTakeReqVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import co.yixiang.yshop.framework.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin write-boundary tests use a real redemption and database mappers. */
class ScoreOrderAdminUpdateDatabaseTest {
    private ScoreOrderDatabaseFixture f;
    private ScoreOrderMapper mapper;
    private ScoreOrderServiceImpl admin;

    @BeforeEach
    void setup() throws Exception {
        f = new ScoreOrderDatabaseFixture();
        f.setup();
        mapper = f.sql.getMapper(ScoreOrderMapper.class);
        admin = new ScoreOrderServiceImpl();
        ReflectionTestUtils.setField(admin, "orderMapper", mapper);
    }

    @AfterEach
    void close() throws Exception {
        if (f != null) f.close();
    }

    @Test
    void dispatchCannotRewriteRedemptionSnapshot() throws Exception {
        long id = redeem();
        ScoreOrderDO before = mapper.selectById(id);

        ScoreOrderController controller = new ScoreOrderController();
        ReflectionTestUtils.setField(controller, "orderService", admin);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String legacyRow = "{\"id\":" + id + ",\"uid\":2,\"orderId\":\"forged\","
                + "\"productId\":999,\"number\":99,\"score\":777,\"totalScore\":76923,"
                + "\"havePaid\":0,\"haveDelivered\":0,\"haveReceived\":1,\"status\":false,"
                + "\"expressSn\":\"SF\",\"expressNumber\":\"TRACK-1\","
                + "\"expressCompany\":\"Synthetic Express\"}";
        mvc.perform(put("/score/order/update").contentType(MediaType.APPLICATION_JSON).content(legacyRow))
                .andExpect(status().isOk());

        ScoreOrderDO after = mapper.selectById(id);
        assertEquals(before.getUid(), after.getUid());
        assertEquals(before.getOrderId(), after.getOrderId());
        assertEquals(before.getProductId(), after.getProductId());
        assertEquals(before.getNumber(), after.getNumber());
        assertEquals(before.getScore(), after.getScore());
        assertEquals(before.getTotalScore(), after.getTotalScore());
        assertEquals(before.getHavePaid(), after.getHavePaid());
        assertEquals(1, after.getHaveDelivered());
        assertEquals(0, after.getHaveReceived());
        assertEquals(9, f.n("SELECT stock FROM yshop_score_product WHERE id=1"));
        assertEquals(1, f.n("SELECT sales FROM yshop_score_product WHERE id=1"));
        f.decimal("900", "SELECT integral FROM yshop_user WHERE id=1");
        assertEquals(1, f.n("SELECT COUNT(*) FROM yshop_user_bill"));
        assertEquals(1, f.n("SELECT COUNT(*) FROM yshop_score_submission"));

        mvc.perform(post("/score/order/take").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":" + id + "}")).andExpect(status().isOk());
        assertEquals(1, f.n("SELECT have_received FROM yshop_score_order WHERE id=?", id));
        mvc.perform(get("/score/order/take").param("id", Long.toString(id)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void staleDispatchCannotClearReceipt() {
        long id = redeem();
        f.db.update("UPDATE yshop_score_order SET have_delivered=1, have_received=1 WHERE id=?", id);

        assertThrows(ServiceException.class, () -> admin.updateOrder(legacyFullRow(id)));

        assertEquals(1, f.n("SELECT have_received FROM yshop_score_order WHERE id=?", id));
    }

    @Test
    void customerCannotReceiveBeforeDispatch() {
        long id = redeem();
        AppScoreOrderServiceImpl app = new AppScoreOrderServiceImpl();
        ReflectionTestUtils.setField(app, "baseMapper", mapper);
        ReflectionTestUtils.setField(app, "orderMapper", mapper);

        assertThrows(ServiceException.class, () -> app.take(1L, id));

        assertEquals(0, f.n("SELECT have_received FROM yshop_score_order WHERE id=?", id));
    }

    @Test
    void memberReceiptReplayIsIdempotentAfterDispatch() {
        long id = redeem();
        f.db.update("UPDATE yshop_score_order SET have_delivered=1 WHERE id=?", id);
        AppScoreOrderServiceImpl app = new AppScoreOrderServiceImpl();
        ReflectionTestUtils.setField(app, "orderMapper", mapper);

        app.take(1L, id);
        app.take(1L, id);

        assertEquals(1, f.n("SELECT have_received FROM yshop_score_order WHERE id=?", id));
    }

    @Test
    void adminReceiptReplayIsIdempotentAfterDispatch() {
        long id = redeem();
        f.db.update("UPDATE yshop_score_order SET have_delivered=1 WHERE id=?", id);

        admin.takeOrder(id);
        admin.takeOrder(id);

        assertEquals(1, f.n("SELECT have_received FROM yshop_score_order WHERE id=?", id));
    }

    @Test
    void memberCannotReceiveAnotherMembersOrder() {
        long id = redeem();
        f.db.update("UPDATE yshop_score_order SET have_delivered=1 WHERE id=?", id);
        AppScoreOrderServiceImpl app = new AppScoreOrderServiceImpl();
        ReflectionTestUtils.setField(app, "orderMapper", mapper);

        assertThrows(ServiceException.class, () -> app.take(2L, id));

        assertEquals(0, f.n("SELECT have_received FROM yshop_score_order WHERE id=?", id));
    }

    @Test
    void receiptMutationsUsePostRoutesAndShippingKeepsPutRoute() throws Exception {
        var adminTake = ScoreOrderController.class.getMethod("take", ScoreOrderTakeReqVO.class);
        var appTake = AppScoreOrderController.class.getMethod("take", ScoreOrderTakeReqVO.class);
        var adminUpdate = ScoreOrderController.class.getMethod("updateOrder", ScoreOrderUpdateReqVO.class);

        assertNotNull(adminTake.getAnnotation(PostMapping.class));
        assertNull(adminTake.getAnnotation(GetMapping.class));
        assertNotNull(appTake.getAnnotation(PostMapping.class));
        assertNull(appTake.getAnnotation(GetMapping.class));
        assertNotNull(adminUpdate.getAnnotation(PutMapping.class));
        assertTrue(hasRequestBody(adminTake));
        assertTrue(hasRequestBody(appTake));
    }

    private boolean hasRequestBody(java.lang.reflect.Method method) {
        return java.util.Arrays.stream(method.getParameterAnnotations()[0])
                .anyMatch(RequestBody.class::isInstance);
    }

    @Test
    void paidRedemptionCannotBeDeleted() {
        long id = redeem();

        assertThrows(ServiceException.class, () -> admin.deleteOrder(id));

        assertEquals(0, f.n("SELECT deleted FROM yshop_score_order WHERE id=?", id));
        assertEquals(1, f.n("SELECT COUNT(*) FROM yshop_score_submission WHERE order_id=(SELECT order_id FROM yshop_score_order WHERE id=?)", id));
    }

    @Test
    void unpaidOrderCanBeDeleted() {
        f.db.update("INSERT INTO yshop_score_order(uid,order_id,product_id,number,score,total_score,have_paid) "
                + "VALUES(1,'synthetic-unpaid',1,1,100,100,0)");
        long id = f.db.queryForObject("SELECT id FROM yshop_score_order", Long.class);

        admin.deleteOrder(id);

        assertEquals(1, f.n("SELECT deleted FROM yshop_score_order WHERE id=?", id));
    }

    @Test
    void unpaidOrderCannotBeDispatched() {
        long id = redeem();
        f.db.update("UPDATE yshop_score_order SET have_paid=0 WHERE id=?", id);

        assertThrows(ServiceException.class, () -> admin.updateOrder(legacyFullRow(id)));

        assertEquals(0, f.n("SELECT have_delivered FROM yshop_score_order WHERE id=?", id));
        assertEquals("", f.db.queryForObject("SELECT express_number FROM yshop_score_order WHERE id=?", String.class, id));
    }

    private long redeem() {
        f.orders.submit(1L, f.request("1"));
        return f.db.queryForObject("SELECT id FROM yshop_score_order", Long.class);
    }

    private ScoreOrderUpdateReqVO legacyFullRow(long id) {
        ScoreOrderUpdateReqVO request = new ScoreOrderUpdateReqVO();
        request.setId(id);
        request.setExpressSn("SF");
        request.setExpressNumber("TRACK-1");
        request.setExpressCompany("Synthetic Express");
        return request;
    }
}
