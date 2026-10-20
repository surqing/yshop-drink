package co.yixiang.yshop.module.store.controller.admin.printer;

import co.yixiang.yshop.framework.apilog.core.annotation.ApiAccessLog;
import co.yixiang.yshop.framework.common.pojo.*;
import co.yixiang.yshop.module.store.service.printer.PrinterService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static co.yixiang.yshop.framework.common.pojo.CommonResult.success;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;

@RestController
@RequestMapping("/store/printer")
@Validated
@Tag(name="管理后台 - 打印机管理")
public class PrinterController {
    private final PrinterService service;
    public PrinterController(PrinterService service) { this.service=service; }
    @GetMapping("/providers") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<List<ProviderInfo>> providers() { return success(service.providers()); }
    @GetMapping("/shops") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<List<ShopOption>> shops() { return success(service.shops()); }
    @GetMapping("/page") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<PageResult<DeviceView>> page(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int pageNo,
            @RequestParam(defaultValue="10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required=false) @Size(max=80) String name,@RequestParam(required=false) Long shopId,
            @RequestParam(defaultValue="false") boolean deleted) {
        return success(service.page(pageNo,pageSize,name,shopId,deleted));
    }
    @GetMapping("/get") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<DeviceView> get(@RequestParam @Positive long id) { return success(service.get(id)); }
    @PostMapping("/create") @PreAuthorize("@ss.hasPermission('store:web-print:create')")
    @ApiAccessLog(requestEnable=false)
    public CommonResult<Long> create(@Valid @RequestBody SaveRequest request) { return success(service.save(request,false)); }
    @PutMapping("/update") @PreAuthorize("@ss.hasPermission('store:web-print:update')")
    @ApiAccessLog(requestEnable=false)
    public CommonResult<Long> update(@Valid @RequestBody SaveRequest request) { return success(service.save(request,true)); }
    @DeleteMapping("/delete") @PreAuthorize("@ss.hasPermission('store:web-print:delete')")
    public CommonResult<Boolean> delete(@RequestParam @Positive long id) { service.delete(id); return success(true); }
    @PostMapping("/authorize") @PreAuthorize("@ss.hasPermission('store:web-print:authorize')")
    public CommonResult<OperationResult> authorize(@RequestParam @Positive long id) { return success(service.authorize(id,false)); }
    @PostMapping("/reconnect") @PreAuthorize("@ss.hasPermission('store:web-print:authorize')")
    public CommonResult<OperationResult> reconnect(@RequestParam @Positive long id) { return success(service.reconnect(id)); }
    @PostMapping("/deauthorize") @PreAuthorize("@ss.hasPermission('store:web-print:authorize')")
    public CommonResult<OperationResult> deauthorize(@RequestParam @Positive long id) { return success(service.authorize(id,true)); }
    @PutMapping("/binding") @PreAuthorize("@ss.hasPermission('store:web-print:binding')")
    public CommonResult<Boolean> bind(@Valid @RequestBody BindingRequest request) { service.bind(request); return success(true); }
    @DeleteMapping("/binding") @PreAuthorize("@ss.hasPermission('store:web-print:binding')")
    public CommonResult<Boolean> unbind(@RequestParam @Positive long id) { service.unbind(id); return success(true); }
    @GetMapping("/status") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<StatusResult> status(@RequestParam @Positive long id) { return success(service.cachedStatus(id)); }
    @PostMapping("/status/refresh") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<StatusResult> refreshStatus(@RequestParam @Positive long id) { return success(service.status(id)); }
    @PostMapping("/test-print") @PreAuthorize("@ss.hasPermission('store:web-print:test')")
    public CommonResult<JobView> test(@Valid @RequestBody TestRequest request) { return success(service.test(request)); }
    @GetMapping("/test-print/get") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<JobView> getJob(@RequestParam @Positive long id,
            @RequestParam @Pattern(regexp="[a-zA-Z0-9_-]{16,64}") String requestId) { return success(service.getJob(id,requestId)); }
    @GetMapping("/test-print/page") @PreAuthorize("@ss.hasPermission('store:web-print:query')")
    public CommonResult<PageResult<JobView>> jobs(@RequestParam @Positive long id,
            @RequestParam(defaultValue="1") @Min(1) @Max(100000) int pageNo,
            @RequestParam(defaultValue="10") @Min(1) @Max(100) int pageSize) { return success(service.jobs(id,pageNo,pageSize)); }
}
