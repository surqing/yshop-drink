package co.yixiang.yshop.module.product.controller.admin.storeproduct;

import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.module.product.service.catalog.BatchStockOperationsService;
import co.yixiang.yshop.module.product.service.catalog.CatalogOptions.IntegerCount;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/product/catalog/stock-batches")
@RequiredArgsConstructor
public class BatchStockOperationsController {
    private final BatchStockOperationsService service;

    public record Line(long productId, long skuId,
                       @JsonDeserialize(using = IntegerCount.class) int expectedStock,
                       @JsonDeserialize(using = IntegerCount.class) int delta) {}
    public record PreviewRequest(long shopId, List<Line> lines, String reason) {}
    public record ApplyRequest(long shopId, List<Line> lines, String reason, String key) {}

    @PostMapping("/preview")
    @PreAuthorize("@ss.hasPermission('shop:store-product:update')")
    public CommonResult<BatchStockOperationsService.Preview> preview(@RequestBody PreviewRequest request) {
        return CommonResult.success(service.preview(request.shopId(), inputs(request.lines()), request.reason()));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('shop:store-product:update')")
    public CommonResult<BatchStockOperationsService.BatchResult> apply(@RequestBody ApplyRequest request) {
        return CommonResult.success(service.apply(request.shopId(), inputs(request.lines()), request.reason(), request.key()));
    }

    @GetMapping("/result")
    @PreAuthorize("@ss.hasPermission('shop:store-product:update')")
    public CommonResult<BatchStockOperationsService.ResultLookup> result(@RequestParam String key) {
        return CommonResult.success(service.result(key));
    }

    private static List<BatchStockOperationsService.LineInput> inputs(List<Line> lines) {
        if (lines == null) return null;
        return lines.stream().map(line -> line == null ? null : new BatchStockOperationsService.LineInput(
                line.productId(), line.skuId(), line.expectedStock(), line.delta())).toList();
    }
}
