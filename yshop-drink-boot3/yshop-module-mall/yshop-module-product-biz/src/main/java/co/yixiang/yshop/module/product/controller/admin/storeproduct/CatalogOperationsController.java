package co.yixiang.yshop.module.product.controller.admin.storeproduct;

import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.module.product.service.catalog.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/product/catalog")
@RequiredArgsConstructor
public class CatalogOperationsController {
    private final CatalogOperationsService service;
    public record Config(long productId,long version,CatalogOptions.Configuration configuration) {}
    public record Stock(long productId,long skuId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=CatalogOptions.IntegerCount.class) int expected,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=CatalogOptions.IntegerCount.class) int delta,String reason,String key) {}
    public record Price(long productId,long skuId,BigDecimal price,long version,String reason,String key) {}
    public record Sale(long productId,long skuId,boolean enabled,long version) {}
    public record Batch(List<Long> ids,Integer sale,Long categoryId) {}
    public record Copy(long sourceId,long targetShopId,long targetCategoryId,String key) {}
    @GetMapping("/configuration") @PreAuthorize("@ss.hasPermission('shop:store-product:query')")
    public CommonResult<Map<String,Object>> configuration(@RequestParam long productId){return CommonResult.success(service.configuration(productId));}
    @PutMapping("/configuration") @PreAuthorize("@ss.hasPermission('shop:store-product:update')")
    public CommonResult<Boolean> configure(@RequestBody Config r){service.configure(r.productId(),r.version(),r.configuration());return CommonResult.success(true);}
    @PostMapping("/stock") @PreAuthorize("@ss.hasPermission('shop:store-product:update')")
    public CommonResult<Boolean> stock(@RequestBody Stock r){service.adjustStock(r.productId(),r.skuId(),r.expected(),r.delta(),r.reason(),r.key());return CommonResult.success(true);}
    @PutMapping("/price") @PreAuthorize("@ss.hasPermission('shop:store-product:update')")
    public CommonResult<Boolean> price(@RequestBody Price r){service.price(r.productId(),r.skuId(),r.price(),r.version(),r.reason(),r.key());return CommonResult.success(true);}
    @PutMapping("/sku-sale") @PreAuthorize("@ss.hasPermission('shop:store-product:update')")
    public CommonResult<Boolean> sale(@RequestBody Sale r){service.skuSale(r.productId(),r.skuId(),r.enabled(),r.version());return CommonResult.success(true);}
    @PostMapping("/batch") @PreAuthorize("@ss.hasPermission('shop:store-product:update')")
    public CommonResult<Boolean> batch(@RequestBody Batch r){service.batch(r.ids(),r.sale(),r.categoryId());return CommonResult.success(true);}
    @PostMapping("/copy") @PreAuthorize("@ss.hasPermission('shop:store-product:create') and @ss.hasPermission('shop:store-product:query')")
    public CommonResult<Long> copy(@RequestBody Copy r){return CommonResult.success(service.copy(r.sourceId(),r.targetShopId(),r.targetCategoryId(),r.key()));}
    @GetMapping("/history") @PreAuthorize("@ss.hasPermission('shop:store-product:query')")
    public CommonResult<List<Map<String,Object>>> history(@RequestParam long productId){return CommonResult.success(service.history(productId));}
}
