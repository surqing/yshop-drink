package co.yixiang.yshop.module.order.ordering;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.yixiang.yshop.module.product.service.storeproduct.*;
import co.yixiang.yshop.module.product.service.storeproduct.dto.*;
import co.yixiang.yshop.module.product.service.storeproductattr.*;
import co.yixiang.yshop.module.product.service.storeproductattrvalue.*;
import co.yixiang.yshop.module.product.service.storeproductattrresult.*;
import co.yixiang.yshop.module.product.service.storeproductrule.*;
import co.yixiang.yshop.module.product.service.category.*;
import co.yixiang.yshop.module.product.dal.mysql.storeproduct.*;
import co.yixiang.yshop.module.product.dal.mysql.storeproductattr.*;
import co.yixiang.yshop.module.product.dal.mysql.storeproductattrvalue.*;
import co.yixiang.yshop.module.product.dal.mysql.storeproductattrresult.*;
import co.yixiang.yshop.module.product.dal.mysql.category.*;
import co.yixiang.yshop.module.store.dal.mysql.storeshop.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.mybatis.spring.SqlSessionTemplate;
import java.util.*;

/** Explicit MySQL acceptance (not an H2 substitute). Exercises original catalog save path. */
class CatalogEditingMysqlAcceptance {
    CatalogDatabaseTest fixture;
    StoreProductServiceImpl products;
    ProductCategoryServiceImpl categories;
    <T> T proxy(T object) {
        var p=new ProxyFactory(object);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(fixture.f.tm,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();
    }
    void field(Object bean,String name,Object value){ReflectionTestUtils.setField(bean,name,value);}
    @BeforeEach void setup() throws Exception {
        if(System.getenv("YSHOP_ORDERING_ACCEPTANCE_CONFIG")==null) throw new IllegalStateException("ISOLATED_MYSQL_ACCEPTANCE_REQUIRED");
        fixture=new CatalogDatabaseTest();fixture.setup();var f=fixture.f;
        var bean=new MybatisSqlSessionFactoryBean();bean.setDataSource(f.data);var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var global=com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();global.getDbConfig().setIdType(com.baomidou.mybatisplus.annotation.IdType.AUTO);global.setMetaObjectHandler(new co.yixiang.yshop.framework.mybatis.core.handler.DefaultDBFieldHandler());bean.setGlobalConfig(global);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);
        for(var mapper:List.of(StoreProductMapper.class,StoreProductAttrMapper.class,StoreProductAttrValueMapper.class,StoreProductAttrResultMapper.class,ProductCategoryMapper.class,StoreShopMapper.class)) config.addMapper(mapper);
        bean.setConfiguration(config);var sql=new SqlSessionTemplate(bean.getObject());var access=f.access(103,false);
        var values=new StoreProductAttrValueServiceImpl();field(values,"storeProductAttrValueMapper",sql.getMapper(StoreProductAttrValueMapper.class));field(values,"baseMapper",sql.getMapper(StoreProductAttrValueMapper.class));
        var results=new StoreProductAttrResultServiceImpl();field(results,"storeProductAttrResultMapper",sql.getMapper(StoreProductAttrResultMapper.class));field(results,"baseMapper",sql.getMapper(StoreProductAttrResultMapper.class));
        var attr=new StoreProductAttrServiceImpl();field(attr,"storeProductAttrMapper",sql.getMapper(StoreProductAttrMapper.class));field(attr,"baseMapper",sql.getMapper(StoreProductAttrMapper.class));field(attr,"storeProductAttrValueMapper",sql.getMapper(StoreProductAttrValueMapper.class));field(attr,"storeProductAttrValueService",values);field(attr,"storeProductAttrResultService",results);field(attr,"catalogJdbc",f.db);
        categories=new ProductCategoryServiceImpl();field(categories,"productCategoryMapper",sql.getMapper(ProductCategoryMapper.class));field(categories,"baseMapper",sql.getMapper(ProductCategoryMapper.class));field(categories,"storeShopMapper",sql.getMapper(StoreShopMapper.class));field(categories,"storeAccess",access);field(categories,"orderingJdbc",f.db);categories=proxy(categories);
        products=new StoreProductServiceImpl();field(products,"storeProductMapper",sql.getMapper(StoreProductMapper.class));field(products,"baseMapper",sql.getMapper(StoreProductMapper.class));field(products,"storeProductAttrValueService",values);field(products,"storeProductAttrService",proxy(attr));field(products,"storeProductAttrResultService",results);field(products,"storeProductRuleService",mock(StoreProductRuleService.class));field(products,"productCategoryService",categories);field(products,"storeShopMapper",sql.getMapper(StoreShopMapper.class));field(products,"storeAccess",access);field(products,"catalog",fixture.catalog);field(products,"catalogJdbc",f.db);products=proxy(products);
    }
    @AfterEach void clear(){if(fixture!=null)fixture.clear();}
    StoreProductDto dto(){var p=new StoreProductDto();p.setShopId(1);p.setCateId("1");p.setImage("/synthetic.png");p.setSliderImage(List.of("/synthetic.png"));p.setStoreName("Synthetic latte");p.setDescription("Synthetic description");p.setStoreInfo("Synthetic");p.setKeyword("");p.setUnitName("杯");p.setSpecType(0);p.setIsShow(1);p.setIsIntegral(0);p.setGiveIntegral(0D);var sku=new ProductFormatDto();sku.setPrice(15D);sku.setStock(5);p.setAttrs(List.of(sku));return p;}
    long created(StoreProductDto p){products.insertAndEditYxStoreProduct(p);return fixture.f.db.queryForObject("SELECT MAX(id) FROM yshop_store_product",Long.class);}
    @Test void originalCreateProducesSkuAndInitialInventoryAudit(){long id=created(dto());assertEquals(5,fixture.n("SELECT stock FROM yshop_store_product WHERE id=?",id));assertEquals(1,fixture.n("SELECT COUNT(*) FROM yshop_product_operation WHERE product_id=? AND kind='STOCK'",id));assertNotNull(products.getProductInfo(id).get("productInfo"));}
    @Test void editPreservesSkuIdStockSalesAndPendingOrderSnapshot(){var p=dto();long id=created(p);long sku=fixture.f.db.queryForObject("SELECT id FROM yshop_store_product_attr_value WHERE product_id=?",Long.class,id);var req=fixture.f.request();req.setProductId(List.of(Long.toString(id)));req.setSpec(List.of("默认"));req.setChoices(List.of(new co.yixiang.yshop.module.product.service.catalog.CatalogOptions.Choice(1,List.of())));String order=fixture.f.place(req);String snapshot=fixture.f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,order);p.setId(id);p.setCatalogVersion(1L);p.setStoreName("Edited name");p.getAttrs().get(0).setPrice(18D);p.getAttrs().get(0).setStock(999);products.insertAndEditYxStoreProduct(p);assertEquals(sku,fixture.n("SELECT id FROM yshop_store_product_attr_value WHERE product_id=?",id));assertEquals(4,fixture.n("SELECT stock FROM yshop_store_product_attr_value WHERE id=?",sku));assertEquals(1,fixture.n("SELECT sales FROM yshop_store_product_attr_value WHERE id=?",sku));assertEquals(snapshot,fixture.f.db.queryForObject("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id=?",String.class,order));fixture.f.orders.cancel(order,1L,false);assertEquals(5,fixture.n("SELECT stock FROM yshop_store_product_attr_value WHERE id=?",sku));}
    @Test void failedCreationDoesNotLeaveOrphans(){var p=dto();p.getAttrs().get(0).setPrice(-1D);assertThrows(RuntimeException.class,()->created(p));assertEquals(2,fixture.n("SELECT COUNT(*) FROM yshop_store_product"));assertEquals(2,fixture.n("SELECT COUNT(*) FROM yshop_store_product_attr_value"));}
    @Test void categoryCrossStoreAndThreeLevelRejected(){var p=new co.yixiang.yshop.module.product.controller.admin.category.vo.ProductCategoryCreateReqVO();p.setShopId(1);p.setParentId(2L);p.setName("Synthetic");p.setPicUrl("/synthetic.png");p.setStatus(0);assertThrows(RuntimeException.class,()->categories.createCategory(p));p.setParentId(1L);long child=categories.createCategory(p);var edit=new co.yixiang.yshop.module.product.controller.admin.category.vo.ProductCategoryUpdateReqVO();edit.setId(1L);edit.setShopId(1);edit.setParentId(child);edit.setName("Synthetic");edit.setPicUrl("/synthetic.png");edit.setStatus(0);assertThrows(RuntimeException.class,()->categories.updateCategory(edit));assertThrows(RuntimeException.class,()->categories.deleteCategory(1L));}
    @Test void multiSkuEditReadbackKeepsExactPricesAndSavingDoesNotZeroThem() {
        var p=dto();p.setSpecType(1);
        p.setItems(List.of(FromatDetailDto.builder().value("杯型").detail(List.of("中杯","大杯")).build()));
        var medium=p.getAttrs().get(0);medium.setDetail(Map.of("杯型","中杯"));medium.setCost(3D);medium.setOtPrice(16D);
        var large=new ProductFormatDto();large.setDetail(Map.of("杯型","大杯"));large.setPrice(18D);large.setCost(4D);large.setOtPrice(19D);large.setStock(7);
        p.setAttrs(List.of(medium,large));long id=created(p);
        var info=(ProductDto)products.getProductInfo(id).get("productInfo");
        assertEquals(List.of(15D,18D),info.getAttrs().stream().map(ProductFormatDto::getPrice).sorted().toList());
        assertEquals(List.of(3D,4D),info.getAttrs().stream().map(ProductFormatDto::getCost).sorted().toList());
        assertEquals(List.of(16D,19D),info.getAttrs().stream().map(ProductFormatDto::getOtPrice).sorted().toList());
        p.setId(id);p.setCatalogVersion(1L);p.setAttrs(info.getAttrs());products.insertAndEditYxStoreProduct(p);
        assertEquals(12,fixture.n("SELECT stock FROM yshop_store_product WHERE id=?",id));
        assertEquals(0,fixture.n("SELECT COUNT(*) FROM yshop_store_product_attr_value WHERE product_id=? AND price=0",id));
    }
    @RepeatedTest(20) void skuEditAndReservationRace() throws Exception {var p=dto();long id=created(p);long sku=fixture.f.db.queryForObject("SELECT id FROM yshop_store_product_attr_value WHERE product_id=?",Long.class,id);var successes=fixture.f.parallel(20,()->{fixture.f.access(103,false);try{if(java.util.concurrent.ThreadLocalRandom.current().nextBoolean()){var edit=dto();edit.setId(id);edit.setCatalogVersion(1L);edit.getAttrs().get(0).setPrice(18D);edit.setSpecType(1);edit.setItems(List.of(FromatDetailDto.builder().value("杯型").detail(List.of("大杯")).build()));edit.getAttrs().get(0).setDetail(Map.of("杯型","大杯"));edit.getAttrs().get(0).setStock(2);products.insertAndEditYxStoreProduct(edit);}else{var req=fixture.f.request();req.setProductId(List.of(Long.toString(id)));req.setSpec(List.of("默认"));req.setChoices(List.of(new co.yixiang.yshop.module.product.service.catalog.CatalogOptions.Choice(1,List.of())));fixture.f.place(req);}return true;}catch(co.yixiang.yshop.framework.common.exception.ServiceException e){return false;}});assertTrue(successes.contains(true));assertEquals(sku,fixture.n("SELECT id FROM yshop_store_product_attr_value WHERE product_id=? AND sku='默认'",id));assertEquals(5-fixture.n("SELECT COUNT(*) FROM yshop_store_order"),fixture.n("SELECT stock FROM yshop_store_product_attr_value WHERE id=?",sku));assertEquals(0,fixture.n("SELECT COUNT(*) FROM yshop_store_order WHERE paid<>0 OR pay_price<>15"));assertEquals(fixture.n("SELECT SUM(stock) FROM yshop_store_product_attr_value WHERE product_id=?",id),fixture.n("SELECT stock FROM yshop_store_product WHERE id=?",id));for(String order:fixture.f.db.queryForList("SELECT order_id FROM yshop_store_order",String.class))fixture.f.orders.cancel(order,1L,false);assertEquals(5,fixture.n("SELECT stock FROM yshop_store_product_attr_value WHERE id=?",sku));assertEquals(fixture.n("SELECT SUM(stock) FROM yshop_store_product_attr_value WHERE product_id=?",id),fixture.n("SELECT stock FROM yshop_store_product WHERE id=?",id));}
}
