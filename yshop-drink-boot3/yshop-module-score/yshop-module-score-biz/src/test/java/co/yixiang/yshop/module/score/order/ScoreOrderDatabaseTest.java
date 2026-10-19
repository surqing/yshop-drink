package co.yixiang.yshop.module.score.order;

import static org.junit.jupiter.api.Assertions.*;
import co.yixiang.yshop.framework.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;

/** Financial assertions use real production mappers and transactional services. */
class ScoreOrderDatabaseTest {
    ScoreOrderDatabaseFixture f;
    @BeforeEach void setup() throws Exception {f=new ScoreOrderDatabaseFixture();f.setup();}
    @AfterEach void close() throws Exception {if(f!=null)f.close();}
    @Test void quantityTwoChargesTotalAndRecordsPostDebitBalance(){f.orders.submit(1L,f.request("2"));f.decimal("800","SELECT integral FROM yshop_user WHERE id=1");assertEquals(8,f.n("SELECT stock FROM yshop_score_product WHERE id=1"));assertEquals(2,f.n("SELECT sales FROM yshop_score_product WHERE id=1"));assertEquals(100,f.n("SELECT score FROM yshop_score_order"));assertEquals(200,f.n("SELECT total_score FROM yshop_score_order"));assertEquals(2,f.n("SELECT number FROM yshop_score_order"));f.decimal("200","SELECT number FROM yshop_user_bill");f.decimal("800","SELECT balance FROM yshop_user_bill");assertEquals(0,f.n("SELECT pm FROM yshop_user_bill"));}
    @Test void singleUnitBillUsesPostDebitBalance(){f.orders.submit(1L,f.request("1"));f.decimal("900","SELECT integral FROM yshop_user WHERE id=1");f.decimal("100","SELECT number FROM yshop_user_bill");f.decimal("900","SELECT balance FROM yshop_user_bill");}
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"0","-1","1.5","abc","2147483648","9999999999999999999999999"," 1","1 ","+1","1e0"})
    void invalidQuantitiesRejectWithoutEffects(String quantity){assertThrows(ServiceException.class,()->f.orders.submit(1L,f.request(quantity)));f.unchanged();}
    @Test void insufficientStockRejectsWithoutEffects(){f.db.update("UPDATE yshop_score_product SET stock=1 WHERE id=1");assertThrows(ServiceException.class,()->f.orders.submit(1L,f.request("2")));assertEquals(1,f.n("SELECT stock FROM yshop_score_product"));assertEquals(0,f.n("SELECT sales FROM yshop_score_product"));assertEquals(0,f.n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(0,f.n("SELECT COUNT(*) FROM yshop_user_bill"));f.decimal("1000","SELECT integral FROM yshop_user WHERE id=1");}
    @Test void totalCostBeyondBalanceRejectsBeforeWrites(){f.db.update("UPDATE yshop_user SET integral=150.50 WHERE id=1");assertThrows(ServiceException.class,()->f.orders.submit(1L,f.request("2")));f.decimal("150.50","SELECT integral FROM yshop_user WHERE id=1");assertEquals(0,f.n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(0,f.n("SELECT COUNT(*) FROM yshop_user_bill"));assertEquals(10,f.n("SELECT stock FROM yshop_score_product"));assertEquals(0,f.n("SELECT sales FROM yshop_score_product"));}
    @ParameterizedTest @ValueSource(strings={"off","deleted"}) void unavailableProductRejectsWithoutEffects(String state){f.db.update("UPDATE yshop_score_product SET "+(state.equals("off")?"is_switch=0":"deleted=1")+" WHERE id=1");assertThrows(ServiceException.class,()->f.orders.submit(1L,f.request("1")));f.unchanged();}
    @Test void totalCostOverflowRejectsWithoutEffects(){f.db.update("UPDATE yshop_score_product SET score=2147483647,stock=10 WHERE id=1");assertThrows(ServiceException.class,()->f.orders.submit(1L,f.request("2")));f.unchanged();}
    @Test void fractionalBalanceIsRetainedExactly(){f.db.update("UPDATE yshop_user SET integral=100.25 WHERE id=1");f.orders.submit(1L,f.request("1"));f.decimal("0.25","SELECT integral FROM yshop_user WHERE id=1");f.decimal("0.25","SELECT balance FROM yshop_user_bill");}
    @Test void foreignAddressRejectsWithoutEffects(){var p=f.request("1");p.setAddressId("2");assertThrows(ServiceException.class,()->f.orders.submit(1L,p));f.unchanged();}
    @ParameterizedTest @ValueSource(strings={"order","stock","integral","bill"})
    void databaseFailureAtEveryWriteRollsBackEverything(String window){String ddl=switch(window){case "order"->"ALTER TABLE yshop_score_order ADD CONSTRAINT synthetic_failure CHECK(number<0)";case "stock"->"ALTER TABLE yshop_score_product ADD CONSTRAINT synthetic_failure CHECK(stock=10)";case "integral"->"ALTER TABLE yshop_user ADD CONSTRAINT synthetic_failure CHECK(integral=1000)";default->"ALTER TABLE yshop_user_bill ADD CONSTRAINT synthetic_failure CHECK(number<0)";};f.db.execute(ddl);assertThrows(RuntimeException.class,()->f.orders.submit(1L,f.request("1")));f.unchanged();}
    @ParameterizedTest @ValueSource(strings={"yshop_score_order","yshop_score_product","yshop_user","yshop_user_bill"})
    void zeroAffectedRowsAtEveryWriteRollBackEverything(String table){f.suppressWrite=table;assertThrows(RuntimeException.class,()->f.orders.submit(1L,f.request("1")));f.unchanged();}
    boolean submit(int index,boolean distinctUsers){long uid=distinctUsers?index+1L:1L;var p=f.request("1");p.setAddressId(Long.toString(uid));try{f.orders.submit(uid,p);return true;}catch(ServiceException expected){assertTrue(Set.of("库存不足","积分不足").contains(expected.getMessage()),"Unexpected business rejection: "+expected.getMessage());return false;}}
    @Test void twoMembersCompetingForLastUnitCommitOnce() throws Exception {f.db.update("UPDATE yshop_score_product SET stock=1 WHERE id=1");assertEquals(1,Collections.frequency(f.race(i->submit(i,true)),true));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_user_bill"));assertEquals(0,f.n("SELECT stock FROM yshop_score_product"));assertEquals(1,f.n("SELECT sales FROM yshop_score_product"));f.decimal("1900","SELECT SUM(integral) FROM yshop_user");}
    @Test void twoOrdersWithBalanceForOneCommitOnce() throws Exception {f.db.update("UPDATE yshop_user SET integral=100 WHERE id=1");assertEquals(1,Collections.frequency(f.race(i->submit(i,false)),true));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(1,f.n("SELECT COUNT(*) FROM yshop_user_bill"));f.decimal("0","SELECT integral FROM yshop_user WHERE id=1");assertEquals(9,f.n("SELECT stock FROM yshop_score_product"));assertEquals(1,f.n("SELECT sales FROM yshop_score_product"));}
}
