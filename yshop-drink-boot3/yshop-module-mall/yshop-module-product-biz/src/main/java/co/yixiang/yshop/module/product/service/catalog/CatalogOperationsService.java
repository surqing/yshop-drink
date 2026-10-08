package co.yixiang.yshop.module.product.service.catalog;

import static co.yixiang.yshop.module.product.service.catalog.CatalogOptions.*;

import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.*;

/** Catalog-only operations. Lock order: shops (copy) -> products by ID -> category -> SKU. */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
public class CatalogOperationsService {
    private final JdbcTemplate jdbc;
    private final StoreAccessService access;
    public void lockShop(long id) {
        access.requireShop(id);
        jdbc.queryForObject("SELECT id FROM yshop_store_shop WHERE id=? AND deleted=0 FOR UPDATE",Long.class,id);
    }

    public Map<String,Object> lockedProduct(long id) {
        access.requireProduct(id);
        var rows=jdbc.queryForList("SELECT * FROM yshop_store_product WHERE id=? AND deleted=0 FOR UPDATE",id);
        if(rows.size()!=1) throw reject("PRODUCT_UNAVAILABLE");
        return rows.get(0);
    }
    public void configure(long id,long expectedVersion,Configuration config) {
        validate(config); var p=lockedProduct(id); version(p,expectedVersion);
        jdbc.update("UPDATE yshop_store_product SET catalog_config=?,catalog_version=catalog_version+1 WHERE id=?",JsonUtils.toJsonString(config),id);
    }
    private static void version(Map<String,Object> p,long expected) {
        if(((Number)p.get("catalog_version")).longValue()!=expected) throw reject("CATALOG_CHANGED_REFRESH_REQUIRED");
    }
    private static String hash(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonUtils.toJsonString(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("CATALOG_HASH_FAILED",e);}
    }
    private Map<String,Object> operation(long productId,String key,String kind,Object payload,String reason) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,64}") || reason==null || reason.isBlank() || reason.length()>200)
            throw reject("CATALOG_OPERATION_KEY_REASON_REQUIRED");
        Long actor=SecurityFrameworkUtils.getLoginUserId(); if(actor==null) throw reject("CATALOG_ACTOR_REQUIRED");
        String fingerprint=hash(List.of(productId,kind,payload,reason));
        jdbc.update("INSERT INTO yshop_product_operation(operation_id,actor_id,request_key,request_hash,product_id,kind,reason) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key",
            UUID.randomUUID().toString().replace("-",""),actor,key,fingerprint,productId,kind,reason);
        var row=jdbc.queryForMap("SELECT * FROM yshop_product_operation WHERE actor_id=? AND request_key=? FOR UPDATE",actor,key);
        if(!fingerprint.equals(row.get("request_hash"))) throw reject("CATALOG_OPERATION_IDEMPOTENCY_CONFLICT");
        return row;
    }
    public void adjustStock(long id,long skuId,int expected,int delta,String reason,String key) {
        if(expected<0 || delta==0 || Math.abs((long)delta)>1000000) throw reject("STOCK_ADJUSTMENT_INVALID");
        var p=lockedProduct(id);
        var op=operation(id,key,"STOCK",List.of(skuId,expected,delta),reason);
        if(op.get("after_value")!=null) return;
        var s=jdbc.queryForMap("SELECT stock FROM yshop_store_product_attr_value WHERE product_id=? AND id=? FOR UPDATE",id,skuId);
        long before=((Number)s.get("stock")).longValue(), after=before+delta;
        if(before!=expected) throw reject("STOCK_CHANGED_REFRESH_REQUIRED");
        if(after<0 || after>1000000) throw reject("STOCK_ADJUSTMENT_INVALID");
        if(jdbc.update("UPDATE yshop_store_product_attr_value SET stock=stock+? WHERE id=? AND product_id=? AND stock=?",delta,skuId,id,before)!=1)
            throw reject("STOCK_ADJUSTMENT_CONFLICT");
        // Adjust AVAILABLE stock only. Existing reservations and sales are never overwritten.
        long sum=jdbc.queryForObject("SELECT COALESCE(SUM(stock),0) FROM yshop_store_product_attr_value WHERE product_id=?",Long.class,id);
        if(sum>Integer.MAX_VALUE) throw reject("STOCK_TOTAL_TOO_LARGE");
        jdbc.update("UPDATE yshop_store_product SET stock=? WHERE id=?",sum,id);
        jdbc.update("UPDATE yshop_product_operation SET sku_id=?,before_value=?,after_value=? WHERE operation_id=?",skuId,before,after,op.get("operation_id"));
    }
    public void price(long id,long skuId,BigDecimal price,long expectedVersion,String reason,String key) {
        price=money(price); if(price.signum()<=0) throw reject("CATALOG_PRICE_INVALID");
        var p=lockedProduct(id); var op=operation(id,key,"PRICE",List.of(skuId,price,expectedVersion),reason);
        if(op.get("after_value")!=null) return;
        version(p,expectedVersion);
        var s=jdbc.queryForMap("SELECT price FROM yshop_store_product_attr_value WHERE product_id=? AND id=? FOR UPDATE",id,skuId);
        jdbc.update("UPDATE yshop_store_product_attr_value SET price=? WHERE id=? AND product_id=?",price,skuId,id);
        jdbc.update("UPDATE yshop_store_product SET price=(SELECT MIN(price) FROM yshop_store_product_attr_value WHERE product_id=?),catalog_version=catalog_version+1 WHERE id=?",id,id);
        jdbc.update("UPDATE yshop_product_operation SET sku_id=?,before_value=?,after_value=? WHERE operation_id=?",skuId,s.get("price"),price,op.get("operation_id"));
    }
    public void skuSale(long id,long skuId,boolean enabled,long expectedVersion) {
        var p=lockedProduct(id); version(p,expectedVersion);
        if(jdbc.update("UPDATE yshop_store_product_attr_value SET is_show=? WHERE id=? AND product_id=?",enabled?1:0,skuId,id)!=1) throw reject("SKU_UNAVAILABLE");
        jdbc.update("UPDATE yshop_store_product SET catalog_version=catalog_version+1 WHERE id=?",id);
    }
    public List<Map<String,Object>> history(long id) {
        access.requireProduct(id);
        return jdbc.queryForList("SELECT operation_id,actor_id,sku_id,kind,before_value,after_value,reason,result_product_id,create_time FROM yshop_product_operation WHERE product_id=? ORDER BY create_time DESC,operation_id DESC LIMIT 200",id);
    }
    public Map<String,Object> configuration(long id) {
        access.requireProduct(id);
        var p=jdbc.queryForMap("SELECT catalog_version,catalog_config FROM yshop_store_product WHERE id=? AND deleted=0",id);
        return Map.of("version",p.get("catalog_version"),"configuration",read(p.get("catalog_config")),"skus",jdbc.queryForList("SELECT id,sku,price,stock,is_show FROM yshop_store_product_attr_value WHERE product_id=? ORDER BY id",id));
    }
    public void batch(List<Long> ids,Integer sale,Long category) {
        if(ids==null || ids.isEmpty() || ids.size()>100 || new HashSet<>(ids).size()!=ids.size() || (sale==null)==(category==null)
           || (sale!=null && sale!=0 && sale!=1)) throw reject("CATALOG_BATCH_INVALID");
        var shops=new TreeSet<Long>();
        for(long id:ids) { access.requireProduct(id); shops.add(jdbc.queryForObject("SELECT shop_id FROM yshop_store_product WHERE id=? AND deleted=0",Long.class,id)); }
        shops.forEach(this::lockShop);
        for(long id:ids.stream().sorted().toList()) lockedProduct(id);
        for(long id:ids.stream().sorted().toList()) {
            var p=lockedProduct(id);
            if(category!=null) category(category,((Number)p.get("shop_id")).longValue());
            if(sale!=null) jdbc.update("UPDATE yshop_store_product SET is_show=? WHERE id=?",sale,id);
            else jdbc.update("UPDATE yshop_store_product SET cate_id=?,catalog_version=catalog_version+1 WHERE id=?",category.toString(),id);
        }
    }
    public void category(long id,long shop) {
        Set<Long> seen=new HashSet<>();
        while(id>0) {
            if(!seen.add(id) || seen.size()>2) throw reject("CATEGORY_HIERARCHY_INVALID");
            var c=jdbc.queryForMap("SELECT shop_id,parent_id,status FROM yshop_store_product_category WHERE id=? AND deleted=0 FOR UPDATE",id);
            if(((Number)c.get("shop_id")).longValue()!=shop || ((Number)c.get("status")).intValue()!=0) throw reject("CATEGORY_UNAVAILABLE_IN_STORE");
            id=((Number)c.get("parent_id")).longValue();
        }
        if(seen.isEmpty()) throw reject("CATEGORY_REQUIRED");
    }
    public long copy(long source,long targetShop,long targetCategory,String key) {
        access.requireProduct(source); access.requireShop(targetShop);
        long sourceShop=jdbc.queryForObject("SELECT shop_id FROM yshop_store_product WHERE id=? AND deleted=0",Long.class,source);
        if(sourceShop==targetShop) throw reject("COPY_REQUIRES_DIFFERENT_STORE");
        jdbc.queryForList("SELECT id FROM yshop_store_shop WHERE id IN (?,?) AND deleted=0 ORDER BY id FOR UPDATE",sourceShop,targetShop);
        var p=lockedProduct(source); category(targetCategory,targetShop);
        var op=operation(source,key,"COPY",List.of(targetShop,targetCategory),"跨门店复制，默认下架且库存为零");
        if(op.get("result_product_id")!=null) return ((Number)op.get("result_product_id")).longValue();
        var kh=new GeneratedKeyHolder();
        jdbc.update(c->{var ps=c.prepareStatement("INSERT INTO yshop_store_product(shop_id,shop_name,image,slider_image,store_name,store_info,keyword,cate_id,price,ot_price,cost,stock,sales,is_show,is_integral,unit_name,description,spec_type,catalog_version,catalog_config) SELECT ?,s.name,p.image,p.slider_image,p.store_name,p.store_info,p.keyword,?,p.price,p.ot_price,p.cost,0,0,0,p.is_integral,p.unit_name,p.description,p.spec_type,1,p.catalog_config FROM yshop_store_product p JOIN yshop_store_shop s ON s.id=? AND s.deleted=0 WHERE p.id=? AND p.deleted=0",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,targetShop);ps.setString(2,Long.toString(targetCategory));ps.setLong(3,targetShop);ps.setLong(4,source);return ps;},kh);
        if(kh.getKey()==null) throw reject("PRODUCT_COPY_FAILED"); long dest=kh.getKey().longValue();
        jdbc.update("INSERT INTO yshop_store_product_attr_value(product_id,sku,price,cost,ot_price,image,stock,sales,`unique`,is_show) SELECT ?,sku,price,cost,ot_price,image,0,0,REPLACE(UUID(),'-',''),is_show FROM yshop_store_product_attr_value WHERE product_id=?",dest,source);
        jdbc.update("INSERT INTO yshop_store_product_attr(product_id,attr_name,attr_values) SELECT ?,attr_name,attr_values FROM yshop_store_product_attr WHERE product_id=?",dest,source);
        jdbc.update("INSERT INTO yshop_store_product_attr_result(product_id,result,change_time) SELECT ?,result,CURRENT_TIMESTAMP FROM yshop_store_product_attr_result WHERE product_id=?",dest,source);
        jdbc.update("UPDATE yshop_product_operation SET result_product_id=? WHERE operation_id=?",dest,op.get("operation_id"));
        return dest;
    }
}
