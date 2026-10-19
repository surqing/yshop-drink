package co.yixiang.yshop.score;

import static org.junit.jupiter.api.Assertions.*;
import co.yixiang.yshop.module.score.order.ScoreOrderDatabaseFixture;
import co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper;
import co.yixiang.yshop.module.order.dal.mysql.storeorderstatus.StoreOrderStatusMapper;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.order.service.storeorder.*;
import co.yixiang.yshop.module.order.service.storeorderstatus.StoreOrderStatusServiceImpl;
import org.junit.jupiter.api.*;

/** Both public production service paths share the real member mapper and transaction manager. */
class ScoreReceiptConcurrencyDatabaseTest {
    ScoreOrderDatabaseFixture f;
    AppStoreOrderService receipts;
    @BeforeEach void setup() throws Exception {
        f=new ScoreOrderDatabaseFixture();f.setup(StoreOrderMapper.class,StoreOrderStatusMapper.class);
        f.createTable("yshop_store_order");f.createTable("yshop_store_order_status");
        f.db.update("INSERT INTO yshop_store_order(id,order_id,uid,real_name,user_phone,user_address,cart_id,total_num,total_price,pay_price,paid,status,refund_status,is_system_del,gain_integral,`unique`,pay_type,mark,cost,create_time)VALUES(1,'synthetic-receipt',1,'synthetic','13800000001','owned','',1,1,1,1,1,0,0,25.25,'synthetic-receipt','synthetic','',0,CURRENT_TIMESTAMP)");
        var status=new StoreOrderStatusServiceImpl();ScoreOrderDatabaseFixture.set(status,"baseMapper",f.sql.getMapper(StoreOrderStatusMapper.class));ScoreOrderDatabaseFixture.set(status,"storeOrderStatusMapper",f.sql.getMapper(StoreOrderStatusMapper.class));
        // Post-commit statistics are unrelated to account mutations and perform no provider I/O.
        ScoreOrderDatabaseFixture.set(status,"asyncStoreOrderService",new AsyncStoreOrderService(){public void orderData(Long uid){} public void getOrderTimeData(){}});
        var target=new AppStoreOrderServiceImpl();ScoreOrderDatabaseFixture.set(target,"storeOrderMapper",f.sql.getMapper(StoreOrderMapper.class));ScoreOrderDatabaseFixture.set(target,"memberUserMapper",f.sql.getMapper(MemberUserMapper.class));ScoreOrderDatabaseFixture.set(target,"billService",f.bills);ScoreOrderDatabaseFixture.set(target,"storeOrderStatusService",f.transactional(status));receipts=f.transactional(target);
    }
    @AfterEach void close() throws Exception {if(f!=null)f.close();}
    @RepeatedTest(10) void redemptionVersusActualReceiptKeepsBothMovements() throws Exception {
        var results=f.race(i->{if(i==0)f.orders.submit(1L,f.request("2"));else receipts.takeOrder("synthetic-receipt",1L);return true;});assertEquals(2,results.size());assertTrue(results.stream().allMatch(Boolean::booleanValue));
        f.decimal("825.25","SELECT integral FROM yshop_user WHERE id=1");assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(3,f.n("SELECT status FROM yshop_store_order WHERE id=1"));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_store_order_status"));assertEquals(2,f.n("SELECT COUNT(*) FROM yshop_user_bill"));f.decimal("200","SELECT number FROM yshop_user_bill WHERE pm=0");f.decimal("25.25","SELECT number FROM yshop_user_bill WHERE pm=1");
        var balances=f.db.queryForList("SELECT balance FROM yshop_user_bill",java.math.BigDecimal.class);assertTrue(balances.stream().anyMatch(b->b.compareTo(new java.math.BigDecimal("825.25"))==0));
        // The first movement has one of these exact hand-derived balances; the second is final.
        assertEquals(1,balances.stream().filter(b->b.compareTo(new java.math.BigDecimal("800"))==0 || b.compareTo(new java.math.BigDecimal("1025.25"))==0).count());assertEquals(8,f.n("SELECT stock FROM yshop_score_product"));assertEquals(2,f.n("SELECT sales FROM yshop_score_product"));
    }
}
