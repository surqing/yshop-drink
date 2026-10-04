package co.yixiang.yshop.module.pay.controller.admin.preflight;

import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.module.pay.preflight.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin only. Never register a PermitAll/actuator exposure for merchant diagnostics. */
@RestController
@RequestMapping("/pay/live-preflight")
public class LivePaymentPreflightController {
    private final LivePaymentPreflightService service;
    private final LivePaymentAuditService audit;

    public LivePaymentPreflightController(
            LivePaymentPreflightService service, LivePaymentAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/check")
    @PreAuthorize("@ss.hasPermission('pay:merchant-details:query')")
    public CommonResult<LivePaymentPreflightService.Report> check(
            @RequestParam("detailsId") String id) {
        return CommonResult.success(service.check(id));
    }

    @GetMapping("/audit")
    @PreAuthorize("@ss.hasPermission('pay:merchant-details:query')")
    public CommonResult<LivePaymentAuditService.Snapshot> audit() {
        return CommonResult.success(audit.snapshot());
    }
}
