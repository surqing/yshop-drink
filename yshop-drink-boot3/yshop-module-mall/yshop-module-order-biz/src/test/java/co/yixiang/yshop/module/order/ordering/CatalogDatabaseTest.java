package co.yixiang.yshop.module.order.ordering;

import static org.junit.jupiter.api.Assertions.*;
import static co.yixiang.yshop.module.product.service.catalog.CatalogOptions.*;

import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import co.yixiang.yshop.module.product.service.catalog.CatalogOperationsService;
import co.yixiang.yshop.module.product.service.catalog.BatchStockOperationsService;
import co.yixiang.yshop.module.product.controller.admin.storeproduct.BatchStockOperationsController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;

/** Same production services and additive migration on H2 and disposable MySQL/InnoDB. */
class CatalogDatabaseTest {
    OrderingDatabaseTest f;
    CatalogOperationsService catalog;
    BatchStockOperationsService stockBatches;
    @BeforeEach void setup() throws Exception {
        f=new OrderingDatabaseTest(); f.setup();
        f.db.execute("DROP TABLE IF EXISTS yshop_product_operation");
        f.db.execute("DROP TABLE IF EXISTS yshop_product_stock_batch");
        if(f.mysql) {
            String seed=Files.readString(f.boot.resolve("sql/yixiang-drink-open.sql"));
            for(String table:List.of("yshop_store_product_attr","yshop_store_product_attr_result")) {
                f.db.execute("DROP TABLE IF EXISTS "+table);
                var matcher=java.util.regex.Pattern.compile("CREATE TABLE `"+table+"`.*?;",java.util.regex.Pattern.DOTALL).matcher(seed);assertTrue(matcher.find());f.db.execute(matcher.group());
            }
            var tx=new TransactionTemplate(f.tm);
            String ddl=Files.readString(f.boot.resolve("sql/migrations/2026-10-08-product-catalog.sql"));
            tx.executeWithoutResult(s->{for(String p:ddl.split(";")) if(!p.isBlank()) f.db.execute(p);});
            String batchDdl=Files.readString(f.boot.resolve("sql/migrations/2026-10-25-product-stock-batch.sql"));
            tx.executeWithoutResult(s->{for(String p:batchDdl.split(";")) if(!p.isBlank()) f.db.execute(p);});
        } else {
            f.db.execute("ALTER TABLE yshop_store_product ADD COLUMN price DECIMAL(10,2)");
            for(String column:List.of("shop_name VARCHAR(100)","ot_price DECIMAL(10,2)","cost DECIMAL(10,2)","unit_name VARCHAR(20)","description TEXT","spec_type INT DEFAULT 0")) f.db.execute("ALTER TABLE yshop_store_product ADD COLUMN "+column);
            for(String column:List.of("ot_price DECIMAL(10,2)","image VARCHAR(100)","`unique` VARCHAR(32)")) f.db.execute("ALTER TABLE yshop_store_product_attr_value ADD COLUMN "+column);
            f.db.execute("CREATE TABLE yshop_store_product_attr(id BIGINT AUTO_INCREMENT PRIMARY KEY,product_id BIGINT,attr_name VARCHAR(100),attr_values VARCHAR(100))");
            f.db.execute("CREATE TABLE yshop_store_product_attr_result(id BIGINT AUTO_INCREMENT PRIMARY KEY,product_id BIGINT,result TEXT,change_time TIMESTAMP)");
            f.db.execute("ALTER TABLE yshop_store_product ADD COLUMN catalog_version BIGINT NOT NULL DEFAULT 0");
            f.db.execute("ALTER TABLE yshop_store_product ADD COLUMN catalog_config TEXT");
            f.db.execute("ALTER TABLE yshop_store_product_attr_value ADD COLUMN is_show INT NOT NULL DEFAULT 1");
            f.db.execute("CREATE TABLE yshop_product_operation(operation_id CHAR(32) PRIMARY KEY,actor_id BIGINT,request_key VARCHAR(64),request_hash VARCHAR(64),product_id BIGINT,sku_id BIGINT,kind VARCHAR(16),before_value DECIMAL(18,2),after_value DECIMAL(18,2),reason VARCHAR(200),result_product_id BIGINT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(actor_id,request_key))");
            f.db.execute("ALTER TABLE yshop_product_operation ADD COLUMN batch_id CHAR(32)");
            f.db.execute("ALTER TABLE yshop_product_operation ADD COLUMN delta_value INT");
            f.db.execute("CREATE TABLE yshop_product_stock_batch(batch_id CHAR(32) PRIMARY KEY,actor_id BIGINT NOT NULL,request_key VARCHAR(64) NOT NULL,request_hash CHAR(64) NOT NULL,shop_id BIGINT NOT NULL,state VARCHAR(16) NOT NULL,result_json MEDIUMTEXT,applied_at TIMESTAMP,UNIQUE(actor_id,request_key))");
        }
        var access=f.access(103,false);
        var proxy=new ProxyFactory(new CatalogOperationsService(f.db,access));proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(f.tm,new AnnotationTransactionAttributeSource()));
        catalog=(CatalogOperationsService)proxy.getProxy();
        var batchProxy=new ProxyFactory(new BatchStockOperationsService(f.db,access));batchProxy.setProxyTargetClass(true);
        batchProxy.addAdvice(new TransactionInterceptor(f.tm,new AnnotationTransactionAttributeSource()));
        stockBatches=(BatchStockOperationsService)batchProxy.getProxy();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    Option option(String id,String name,String price){return new Option(id,name,new BigDecimal(price),true,0);}
    Configuration config(){return new Configuration(List.of(
        new Group("temperature","温度","CUSTOM",false,1,1,1,true,null,List.of(option("hot","热","0"),option("cold","冰","0"))),
        new Group("sugar","甜度","CUSTOM",false,1,1,1,true,null,List.of(option("none","无糖","0"),option("half","五分糖","0"))),
        new Group("ice","冰量","CUSTOM",false,1,1,1,true,new Condition("temperature","cold"),List.of(option("normal","正常冰","0"),option("less","少冰","0"))),
        new Group("toppings","加料","TOPPING",true,0,3,2,true,null,List.of(option("pearl","珍珠","2"),option("cream","奶盖","3")))));}
    List<Selection> hot(){return List.of(new Selection("temperature","hot",1),new Selection("sugar","half",1));}
    String key(){return UUID.randomUUID().toString();}
    void configured(){catalog.configure(1,0,config());}
    co.yixiang.yshop.module.order.controller.app.order.param.AppOrderParam request(List<Selection> selected,int qty){var p=f.request();p.setChoices(List.of(new Choice(1,selected)));p.setNumber(List.of(Integer.toString(qty)));return p;}
    long n(String sql,Object... args){return f.count(sql,args);}
    @Test void syntheticConfigCreatesAuthoritativeSnapshotAndCoupon() {
        configured(); var selected=new ArrayList<>(hot());selected.add(new Selection("toppings","pearl",2));
        var p=request(selected,3);p.setCouponId("1");String id=f.place(p);
        assertEquals(new BigDecimal("15.59"),f.db.queryForObject("SELECT pay_price FROM yshop_store_order WHERE order_id=?",BigDecimal.class,id));
        String snapshot=f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,id);
        assertTrue(snapshot.contains("珍珠")); assertTrue(snapshot.contains("15.69"));
        assertEquals(7,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        catalog.configure(1,1,new Configuration(List.of()));
        assertEquals(snapshot,f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,id));
        f.orders.cancel(id,1L,false); f.orders.cancel(id,1L,false);
        assertEquals(10,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(0,n("SELECT status FROM yshop_coupon_user WHERE id=1"));
    }
    @Test void toppingsCannotMakeAnInvalidBasePriceOrderable(){configured();f.db.update("UPDATE yshop_store_product_attr_value SET price=0 WHERE id=1");var s=new ArrayList<>(hot());s.add(new Selection("toppings","pearl",1));assertThrows(RuntimeException.class,()->f.place(request(s,1)));assertEquals(0,n("SELECT COUNT(*) FROM yshop_store_order"));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));}
    @Test void requiredCannotBeOmitted(){configured();assertThrows(RuntimeException.class,()->f.place(request(List.of(),1)));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));}
    @Test void hotCannotChooseIce(){configured();var s=new ArrayList<>(hot());s.add(new Selection("ice","normal",1));assertThrows(RuntimeException.class,()->f.place(request(s,1)));}
    @Test void coldNeedsIce(){configured();assertThrows(RuntimeException.class,()->f.place(request(List.of(new Selection("temperature","cold",1),new Selection("sugar","none",1)),1)));}
    @ParameterizedTest @ValueSource(strings={"unknownGroup","unknownOption","duplicate","negative","max","single","disabled","version"})
    void rejectsInvalidSelections(String bad){
        configured();var s=new ArrayList<>(hot());
        switch(bad){
            case "unknownGroup"->s.add(new Selection("foreign","pearl",1));
            case "unknownOption"->s.add(new Selection("toppings","foreign",1));
            case "duplicate"->{s.add(new Selection("toppings","pearl",1));s.add(new Selection("toppings","pearl",1));}
            case "negative"->s.add(new Selection("toppings","pearl",-1));
            case "max"->s.add(new Selection("toppings","pearl",3));
            case "single"->s.add(new Selection("temperature","cold",1));
            case "disabled"->{var c=config();var groups=new ArrayList<>(c.groups());var old=groups.get(3);groups.set(3,new Group(old.id(),old.name(),old.kind(),old.multiple(),old.min(),old.max(),old.maxPerOption(),false,null,old.options()));catalog.configure(1,1,new Configuration(groups));}
            default->catalog.configure(1,1,config());
        }
        assertThrows(RuntimeException.class,()->f.place(request(s,1)));
        assertEquals(0,n("SELECT COUNT(*) FROM yshop_store_order"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_order_submission"));
        assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));
    }
    @Test void sameSkuDifferentCustomizationsCanCoexist(){configured();var p=request(hot(),1);p.setProductId(List.of("1","1"));p.setSpec(List.of("SKU1","SKU1"));p.setNumber(List.of("1","2"));var other=new ArrayList<>(hot());other.add(new Selection("toppings","cream",1));p.setChoices(List.of(new Choice(1,hot()),new Choice(1,other)));String id=f.place(p);assertEquals(3,n("SELECT SUM(quantity) FROM yshop_order_inventory_reservation WHERE order_id=?",id));f.orders.cancel(id,1L,false);assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));}
    @Test void retryDoesNotRepriceCommittedOrder(){configured();var p=request(hot(),1);String id=f.place(p);catalog.price(1,1,new BigDecimal("18"),1,"synthetic price change",key());assertEquals(id,f.place(p));assertEquals(new BigDecimal("1.23"),f.db.queryForObject("SELECT pay_price FROM yshop_store_order WHERE order_id=?",BigDecimal.class,id));}
    @Test void malformedConfigurationRollsBack(){assertThrows(RuntimeException.class,()->catalog.configure(1,0,new Configuration(List.of(new Group("g","x","CUSTOM",false,1,1,1,true,null,List.of(option("o","x","-1")))))));assertEquals(0,n("SELECT catalog_version FROM yshop_store_product WHERE id=1"));}
    @ParameterizedTest @ValueSource(strings={"-0.01","1.001","1000000"}) void invalidMoney(String s){assertThrows(RuntimeException.class,()->money(new BigDecimal(s)));}
    @Test void futureDependencyRejected(){assertThrows(RuntimeException.class,()->validate(new Configuration(List.of(new Group("a","a","CUSTOM",false,0,1,1,true,new Condition("b","x"),List.of(option("v","v","0")))))));}
    @ParameterizedTest @ValueSource(strings={"1.5","\"1\"","2147483648"}) void jsonQuantityCannotBeCoerced(String value){assertThrows(RuntimeException.class,()->JsonUtils.parseObject("{\"groupId\":\"topping\",\"optionId\":\"pearl\",\"quantity\":"+value+"}",Selection.class));}
    @Test void stockAdjustmentAuditAndReplay(){String key=key();catalog.adjustStock(1,1,10,-10,"synthetic sold out",key);catalog.adjustStock(1,1,10,-10,"synthetic sold out",key);assertEquals(0,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(1,n("SELECT COUNT(*) FROM yshop_product_operation"));assertThrows(RuntimeException.class,()->catalog.adjustStock(1,1,0,5,"synthetic sold out",key));catalog.adjustStock(1,1,0,5,"synthetic restock",key());assertEquals(5,n("SELECT stock FROM yshop_store_product WHERE id=1"));}
    @Test void negativeStockCannotCreateAudit(){assertThrows(RuntimeException.class,()->catalog.adjustStock(1,1,10,-11,"negative",key()));assertEquals(0,n("SELECT COUNT(*) FROM yshop_product_operation"));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));}
    @Test void inventoryAuditFailureRollsBackSkuAndProduct(){f.db.execute("ALTER TABLE yshop_product_operation ADD CONSTRAINT synthetic_audit_failure CHECK(after_value IS NULL)");assertThrows(RuntimeException.class,()->catalog.adjustStock(1,1,10,-2,"synthetic rollback",key()));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(10,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_product_operation"));}
    @Test void priceAuditFailureRollsBackPriceAndVersion(){f.db.execute("ALTER TABLE yshop_product_operation ADD CONSTRAINT synthetic_price_failure CHECK(after_value IS NULL)");assertThrows(RuntimeException.class,()->catalog.price(1,1,new BigDecimal("18"),0,"synthetic rollback",key()));assertEquals(new BigDecimal("1.23"),f.db.queryForObject("SELECT price FROM yshop_store_product_attr_value WHERE id=1",BigDecimal.class));assertEquals(0,n("SELECT catalog_version FROM yshop_store_product WHERE id=1"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_product_operation"));}
    @Test void twoStoreSameNamedDrinksUseIndependentPrices(){f.db.update("UPDATE yshop_store_product SET store_name='synthetic latte' WHERE id IN (1,2)");catalog.price(1,1,new BigDecimal("18"),0,"synthetic A price",key());catalog.price(2,2,new BigDecimal("20"),0,"synthetic B price",key());var a=f.request();a.setChoices(List.of(new Choice(1,List.of())));String aId=f.place(a);var b=f.request();b.setShopId("2");b.setProductId(List.of("2"));b.setSpec(List.of("SKU2"));b.setChoices(List.of(new Choice(1,List.of())));String bId=f.place(b);assertEquals(new BigDecimal("18.00"),f.db.queryForObject("SELECT pay_price FROM yshop_store_order WHERE order_id=?",BigDecimal.class,aId));assertEquals(new BigDecimal("20.00"),f.db.queryForObject("SELECT pay_price FROM yshop_store_order WHERE order_id=?",BigDecimal.class,bId));assertEquals(9,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(9,n("SELECT stock FROM yshop_store_product WHERE id=2"));f.orders.cancel(aId,1L,false);assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(9,n("SELECT stock FROM yshop_store_product WHERE id=2"));}
    @Test void adjustmentKeepsReservation(){String id=f.place(f.request());catalog.adjustStock(1,1,9,-9,"sold out available",key());f.orders.cancel(id,1L,false);assertEquals(1,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertEquals(1,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));}
    @Test void skuAndAdminCrossStoreAttacksRejected(){f.access(101,false);assertThrows(RuntimeException.class,()->catalog.adjustStock(2,2,10,-1,"cross-store",key()));assertThrows(RuntimeException.class,()->catalog.adjustStock(1,2,10,-1,"cross-sku",key()));assertEquals(0,n("SELECT COUNT(*) FROM yshop_product_operation"));}
    @Test void stoppedSkuCannotOrder(){catalog.skuSale(1,1,false,0);var p=f.request();p.setChoices(List.of(new Choice(1,List.of())));assertThrows(RuntimeException.class,()->f.place(p));assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));}
    @Test void productCopyIndependentAndIdempotent(){configured();String key=key();long dest=catalog.copy(1,2,2,key);assertEquals(dest,catalog.copy(1,2,2,key));assertEquals(0,n("SELECT stock FROM yshop_store_product WHERE id=?",dest));assertEquals(0,n("SELECT is_show FROM yshop_store_product WHERE id=?",dest));assertEquals(1,n("SELECT COUNT(*) FROM yshop_store_product_attr_value WHERE product_id=? AND id<>1",dest));long sku=f.db.queryForObject("SELECT id FROM yshop_store_product_attr_value WHERE product_id=?",Long.class,dest);catalog.adjustStock(dest,sku,0,8,"synthetic target restock",key());assertEquals(10,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));assertEquals(8,n("SELECT stock FROM yshop_store_product WHERE id=?",dest));assertThrows(RuntimeException.class,()->catalog.copy(1,2,1,key()));}
    @Test void batchAllOrNothingOnCrossStore(){f.access(101,false);assertThrows(RuntimeException.class,()->catalog.batch(List.of(1L,2L),0,null));assertEquals(2,n("SELECT COUNT(*) FROM yshop_store_product WHERE is_show=1"));}
    @Test void batchWrongCategoryRollsBack(){assertThrows(RuntimeException.class,()->catalog.batch(List.of(1L,2L),null,1L));assertEquals("2",f.db.queryForObject("SELECT cate_id FROM yshop_store_product WHERE id=2",String.class));}

    private BatchStockOperationsService.LineInput line(long product,long sku,int expected,int delta){
        return new BatchStockOperationsService.LineInput(product,sku,expected,delta);
    }
    private String batchKey(){return "batch_"+UUID.randomUUID().toString().replace("-","");}
    private long secondSku(){
        f.db.update("INSERT INTO yshop_store_product_attr_value(id,product_id,sku,stock,sales,price,cost"+(f.mysql?",ot_price,image,`unique`,is_show":"")+") VALUES(3,1,'SKU1-SECOND',5,4,1.23,0"+(f.mysql?",0,'','sku1-second-unique',1":"")+")");
        f.db.update("UPDATE yshop_store_product SET stock=15 WHERE id=1");
        return 3L;
    }
    private long count(String sql,Object... args){return f.db.queryForObject(sql,Long.class,args);}

    @Test void batchStockPreviewIsReadOnlyAndReportsExpectedStockConflict(){
        var valid=stockBatches.preview(1,List.of(line(1,1,10,3))," restock ");
        assertTrue(valid.eligible());assertEquals(10L,valid.rows().get(0).currentStock());assertEquals(13L,valid.rows().get(0).afterStock());
        var stale=stockBatches.preview(1,List.of(line(1,1,9,3)),"restock");
        assertFalse(stale.eligible());assertEquals("STOCK_CHANGED_REFRESH_REQUIRED",stale.rows().get(0).conflictCode());
        assertEquals(10,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(10,n("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(0,count("SELECT COUNT(*) FROM yshop_product_stock_batch"));
        assertEquals(0,count("SELECT COUNT(*) FROM yshop_product_operation"));
    }

    @Test void batchStockAppliesMultipleSkusAtomicallyAndReplaysNormalizedRequest(){
        long sku2=secondSku();String key=batchKey();var lines=List.of(line(1,1,10,3),line(1,sku2,5,-2));
        var first=stockBatches.apply(1,lines," shelf restock ",key);
        var replay=stockBatches.apply(1,List.of(lines.get(1),lines.get(0)),"shelf restock",key);
        assertEquals(first.batchId(),replay.batchId());assertEquals("APPLIED",replay.state());
        assertEquals(13,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(3,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=3"));
        assertEquals(16,n("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(0,n("SELECT sales FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(4,n("SELECT sales FROM yshop_store_product_attr_value WHERE id=3"));
        assertEquals(2,count("SELECT COUNT(*) FROM yshop_product_operation WHERE batch_id=?",first.batchId()));
        assertEquals(List.of(-2,3),f.db.queryForList("SELECT delta_value FROM yshop_product_operation WHERE batch_id=? ORDER BY delta_value",Integer.class,first.batchId()));
        assertEquals("CONFIRMED",stockBatches.result(key).status());
        assertThrows(RuntimeException.class,()->stockBatches.apply(1,lines,"different reason",key));
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_stock_batch"));
        assertEquals(2,count("SELECT COUNT(*) FROM yshop_product_operation"));
    }

    @Test void batchStockStaleSecondSkuRejectsEveryLineWithoutAudit(){
        long sku2=secondSku();f.db.update("UPDATE yshop_store_product_attr_value SET stock=4 WHERE id=?",sku2);
        f.db.update("UPDATE yshop_store_product SET stock=14 WHERE id=1");
        var error=assertThrows(co.yixiang.yshop.framework.common.exception.ServiceException.class,
                ()->stockBatches.apply(1,List.of(line(1,1,10,3),line(1,sku2,5,-2)),"reconcile",batchKey()));
        assertEquals("STOCK_CHANGED_REFRESH_REQUIRED",error.getMessage());
        assertEquals(10,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(4,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=3"));
        assertEquals(14,n("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(0,count("SELECT COUNT(*) FROM yshop_product_stock_batch"));
        assertEquals(0,count("SELECT COUNT(*) FROM yshop_product_operation"));
    }

    @Test void batchStockSecondAuditFailureRollsBackHeaderAndFirstSku(){
        long sku2=secondSku();
        f.db.execute("ALTER TABLE yshop_product_operation ADD CONSTRAINT synthetic_batch_second_audit CHECK(sku_id<>"+sku2+")");
        assertThrows(RuntimeException.class,()->stockBatches.apply(1,List.of(line(1,1,10,3),line(1,sku2,5,-2)),"audit fail",batchKey()));
        assertEquals(10,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(5,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=3"));
        assertEquals(15,n("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(0,count("SELECT COUNT(*) FROM yshop_product_stock_batch"));
        assertEquals(0,count("SELECT COUNT(*) FROM yshop_product_operation"));
    }

    @Test void batchStockSameKeyConcurrentCallsApplyExactlyOnce() throws Exception {
        var requestKey=batchKey();var lines=List.of(line(1,1,10,3));
        var batchIds=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var results=f.parallel(2,()->{f.access(103,false);batchIds.add(stockBatches.apply(1,lines,"concurrent restock",requestKey).batchId());return true;});
        assertEquals(List.of(true,true),results);assertEquals(2,batchIds.size());assertEquals(batchIds.get(0),batchIds.get(1));
        assertEquals(13,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(13,n("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_stock_batch"));
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_operation"));
    }

    @Test void batchStockDifferentKeysCompeteOnExpectedValueWithoutPartialWrite() throws Exception {
        var applied=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var outcomes=f.parallel(2,()->{f.access(103,false);try{applied.add(stockBatches.apply(1,List.of(line(1,1,10,2)),"competing restock",batchKey()).batchId());return true;}
            catch(co.yixiang.yshop.framework.common.exception.ServiceException error){if(!"STOCK_CHANGED_REFRESH_REQUIRED".equals(error.getMessage()))throw error;return false;}});
        assertEquals(1,outcomes.stream().filter(Boolean::booleanValue).count());assertEquals(1,applied.size());
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_stock_batch WHERE state='APPLIED'"));
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_operation WHERE kind='STOCK' AND batch_id IS NOT NULL"));
        assertTrue(List.of(12L, 10L).contains(count("SELECT stock FROM yshop_store_product_attr_value WHERE id=1")));
        assertEquals(count("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"),count("SELECT stock FROM yshop_store_product WHERE id=1"));
    }

    @Test void batchStockRejectsInvalidAndCrossStoreRequestsWithoutDisclosureOrWrites(){
        assertThrows(RuntimeException.class,()->stockBatches.preview(1,List.of(),"empty"));
        assertThrows(RuntimeException.class,()->stockBatches.preview(1,List.of(line(1,1,10,1),line(1,1,10,2)),"duplicate"));
        var tooMany=new ArrayList<BatchStockOperationsService.LineInput>();for(int i=0;i<101;i++)tooMany.add(line(1,i+1,0,1));
        assertThrows(RuntimeException.class,()->stockBatches.preview(1,tooMany,"too many"));
        f.access(101,false);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                ()->stockBatches.preview(1,List.of(line(1,1,10,1),line(2,2,10,1)),"cross-store"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                ()->stockBatches.apply(1,List.of(line(1,1,10,1),line(2,2,10,1)),"cross-store",batchKey()));
        assertEquals(10,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(10,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=2"));
        assertEquals(0,count("SELECT COUNT(*) FROM yshop_product_stock_batch"));
        assertEquals(0,count("SELECT COUNT(*) FROM yshop_product_operation"));
    }

    @Test void batchStockSupportsExactlyOneHundredUniqueSkus(){
        for(int id=3;id<=101;id++){
            if(f.mysql)f.db.update("INSERT INTO yshop_store_product_attr_value(id,product_id,sku,stock,sales,price,cost,ot_price,image,`unique`,is_show) VALUES(?,1,?,0,0,1.23,0,0,'',?,1)",id,"SKU-"+id,"unique-"+id);
            else f.db.update("INSERT INTO yshop_store_product_attr_value(id,product_id,sku,stock,sales,price,cost) VALUES(?,1,?,0,0,1.23,0)",id,"SKU-"+id);
        }
        var lines=new ArrayList<BatchStockOperationsService.LineInput>();
        lines.add(line(1,1,10,1));for(int id=3;id<=101;id++)lines.add(line(1,id,0,1));
        var result=stockBatches.apply(1,lines,"one hundred sku restock",batchKey());
        assertEquals(100,result.lines().size());assertEquals(110,n("SELECT SUM(stock) FROM yshop_store_product_attr_value WHERE product_id=1"));
        assertEquals(110,n("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(100,count("SELECT COUNT(*) FROM yshop_product_operation WHERE batch_id=?",result.batchId()));
    }

    @Test void batchStockPreservesReservationAndHistoricalOrderSnapshot(){
        var order=f.request();order.setNumber(List.of("2"));String orderId=f.place(order);
        String snapshot=f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,orderId);
        assertEquals(8,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        var result=stockBatches.apply(1,List.of(line(1,1,8,3)),"post reservation restock",batchKey());
        assertEquals(11,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        f.orders.cancel(orderId,1L,false);f.orders.cancel(orderId,1L,false);
        assertEquals(13,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(13,n("SELECT stock FROM yshop_store_product WHERE id=1"));
        assertEquals(snapshot,f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,orderId));
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_operation WHERE batch_id=?",result.batchId()));
        assertEquals(2,n("SELECT quantity FROM yshop_order_inventory_reservation WHERE order_id=?",orderId));
    }

    @Test void batchStockResultAndReplayRequireCurrentShopPermission(){
        var key=batchKey();stockBatches.apply(1,List.of(line(1,1,10,1)),"permission check",key);
        f.db.update("UPDATE yshop_store_shop SET admin_id='101' WHERE id=1");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->stockBatches.result(key));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->stockBatches.apply(1,List.of(line(1,1,10,1)),"permission check",key));
        f.access(104,false);
        assertEquals("UNCONFIRMED",stockBatches.result(key).status());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                ()->stockBatches.apply(1,List.of(line(1,1,10,1)),"permission check",key));
        assertEquals(11,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_stock_batch"));
    }
    @ParameterizedTest @ValueSource(strings={"\"10\"","true","10.5","2147483648"})
    void batchStockApiRejectsNonIntegerOrOverflowQuantities(String value){
        String json="{\"shopId\":1,\"lines\":[{\"productId\":1,\"skuId\":1,\"expectedStock\":"+value+",\"delta\":1}],\"reason\":\"strict integer\"}";
        assertThrows(RuntimeException.class,()->JsonUtils.parseObject(json,BatchStockOperationsController.PreviewRequest.class));
    }
    @Test void batchStockSameKeyRejectsEveryChangedSemanticField(){
        var key=batchKey();var original=List.of(line(1,1,10,1));
        stockBatches.apply(1,original,"semantic key",key);
        assertThrows(RuntimeException.class,()->stockBatches.apply(1,List.of(line(1,1,9,1)),"semantic key",key));
        assertThrows(RuntimeException.class,()->stockBatches.apply(1,List.of(line(1,1,10,2)),"semantic key",key));
        assertThrows(RuntimeException.class,()->stockBatches.apply(2,List.of(line(2,2,10,1)),"semantic key",key));
        assertThrows(RuntimeException.class,()->stockBatches.apply(1,original,"different reason",key));
        assertEquals(11,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_stock_batch"));
        assertEquals(1,count("SELECT COUNT(*) FROM yshop_product_operation"));
    }
    @Test void priceDoesNotChangeInventoryOrOldSnapshot(){String id=f.place(f.request());String old=f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,id);catalog.price(1,1,new BigDecimal("20"),0,"synthetic",key());assertEquals(9,n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));assertEquals(old,f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,id));}
    @RepeatedTest(20) void twentyBuyersLastUnit() throws Exception {configured();f.db.update("UPDATE yshop_store_product SET stock=1 WHERE id=1");f.db.update("UPDATE yshop_store_product_attr_value SET stock=1 WHERE id=1");var r=f.parallel(20,()->{try{f.place(request(hot(),1));return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException ex){return false;}});assertEquals(1,Collections.frequency(r,true));assertEquals(0,n("SELECT stock FROM yshop_store_product WHERE id=1"));}
    @RepeatedTest(20) void priceChangeVersusTwentyOrders() throws Exception {
        configured();var r=f.parallel(20,()->{f.access(103,false);try {if(java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) {catalog.price(1,1,new BigDecimal("18"),1,"synthetic race",key());} else f.place(request(hot(),1));return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException ex){return false;}});
        assertTrue(r.contains(true));assertEquals(0,n("SELECT COUNT(*) FROM yshop_store_order WHERE pay_price<>1.23 OR paid<>0"));
        assertEquals(10-n("SELECT COUNT(*) FROM yshop_store_order"),n("SELECT stock FROM yshop_store_product_attr_value WHERE id=1"));
    }
    @RepeatedTest(20) void inventoryAdjustmentVersusTwentyOrders() throws Exception {
        var r=f.parallel(20,()->{f.access(103,false);try {if(java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) catalog.adjustStock(1,1,10,-5,"synthetic race",key());else f.place(f.request());return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException ex){return false;}});
        assertTrue(r.contains(true));long adjustment=n("SELECT COUNT(*) FROM yshop_product_operation WHERE kind='STOCK'");
        long orders=n("SELECT COUNT(*) FROM yshop_store_order");assertEquals(10-5*adjustment-orders,n("SELECT stock FROM yshop_store_product WHERE id=1"));assertTrue(n("SELECT stock FROM yshop_store_product WHERE id=1")>=0);
    }
    @Test void migrationRerunPreservesUnpaidSnapshot() throws Exception {
        if(!f.mysql) return;configured();String id=f.place(request(hot(),1));String old=f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,id);
        var tx=new TransactionTemplate(f.tm);String ddl=Files.readString(f.boot.resolve("sql/migrations/2026-10-08-product-catalog.sql"));tx.executeWithoutResult(s->{for(String part:ddl.split(";")) if(!part.isBlank())f.db.execute(part);});
        assertEquals(old,f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,id));
        assertEquals(0,n("SELECT paid FROM yshop_store_order WHERE order_id=?",id));
    }
}
