package co.yixiang.yshop.module.store.controller.admin.printer;

import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.module.store.service.printer.OrderPrintJobService;
import co.yixiang.yshop.module.store.service.printer.OrderPrintJobWorker.JobView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static co.yixiang.yshop.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/store/order-print-job")
@Validated
public class OrderPrintJobController {
    private final OrderPrintJobService service;
    public OrderPrintJobController(OrderPrintJobService service) { this.service=service; }

    @GetMapping("/page") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<PageResult<JobView>> page(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int pageNo,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required=false) @Positive Long shopId) {
        return success(service.page(pageNo,pageSize,shopId));
    }

    @GetMapping("/get") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<JobView> get(@RequestParam @NotNull Long id) { return success(service.get(id)); }

    @PostMapping("/refresh") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<JobView> refresh(@RequestParam @NotNull Long id) { return success(service.refresh(id)); }

    @PostMapping("/reprint") @PreAuthorize("@ss.hasPermission('store:web-print:test')")
    public CommonResult<JobView> reprint(@Valid @RequestBody OrderPrintJobService.ReprintRequest request) {
        return success(service.reprint(request));
    }

    @PostMapping("/resolve-unknown") @PreAuthorize("@ss.hasPermission('store:web-print:test')")
    public CommonResult<JobView> resolveUnknown(@Valid @RequestBody OrderPrintJobService.ResolveUnknownRequest request) {
        return success(service.resolveUnknown(request));
    }
}
