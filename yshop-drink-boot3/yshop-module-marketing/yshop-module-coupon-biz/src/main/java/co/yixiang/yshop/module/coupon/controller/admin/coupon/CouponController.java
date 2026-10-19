package co.yixiang.yshop.module.coupon.controller.admin.coupon;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.constraints.*;
import jakarta.validation.*;
import java.util.*;
import java.io.IOException;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.common.pojo.CommonResult;
import static co.yixiang.yshop.framework.common.pojo.CommonResult.success;

import co.yixiang.yshop.framework.excel.core.util.ExcelUtils;

import co.yixiang.yshop.module.coupon.controller.admin.coupon.vo.*;
import co.yixiang.yshop.module.coupon.dal.dataobject.coupon.CouponDO;
import co.yixiang.yshop.module.coupon.convert.coupon.CouponConvert;
import co.yixiang.yshop.module.coupon.service.coupon.CouponService;

@Tag(name = "管理后台 - 优惠券")
@RestController
@RequestMapping("/coupon/")
@Validated
public class CouponController {

    @Resource
    private CouponService Service;
    @Resource
    private co.yixiang.yshop.module.coupon.service.marketing.CouponMarketingService marketing;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @PostMapping("/create")
    @Operation(summary = "创建优惠券")
    @PreAuthorize("@ss.hasPermission('coupon::create')")
    public CommonResult<Long> create(@Valid @RequestBody CouponCreateReqVO createReqVO) {
        return success(Service.create(createReqVO));
    }

    @PostMapping("/create-code")
    @PreAuthorize("@ss.hasPermission('coupon::create')")
    public CommonResult<CouponService.CodeCreation> createCode(@Valid @RequestBody CouponCreateReqVO request,
                                                               HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return success(Service.createCode(request));
    }

    @PutMapping("/update")
    @Operation(summary = "更新优惠券")
    @PreAuthorize("@ss.hasPermission('coupon::update')")
    public CommonResult<Boolean> update(@Valid @RequestBody CouponUpdateReqVO updateReqVO) {
        Service.update(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除优惠券")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('coupon::delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        Service.delete(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得优惠券")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('coupon::query')")
    public CommonResult<CouponRespVO> get(@RequestParam("id") Long id) {
        CouponDO couponDO = Service.get(id);
        return success(CouponConvert.INSTANCE.convert(couponDO));
    }

    @GetMapping("/list")
    @Operation(summary = "获得优惠券列表")
    @Parameter(name = "ids", description = "编号列表", required = true, example = "1024,2048")
    @PreAuthorize("@ss.hasPermission('coupon::query')")
    public CommonResult<List<CouponRespVO>> getList() {
        List<CouponDO> list = Service.getList();
        return success(CouponConvert.INSTANCE.convertList(list));
    }

    @GetMapping("/page")
    @Operation(summary = "获得优惠券分页")
    @PreAuthorize("@ss.hasPermission('coupon::query')")
    public CommonResult<PageResult<CouponRespVO>> getPage(@Valid CouponPageReqVO pageVO) {
        PageResult<CouponDO> pageResult = Service.getPage(pageVO);
        return success(CouponConvert.INSTANCE.convertPage(pageResult));
    }

    @GetMapping("/export-excel")
    @Operation(summary = "导出优惠券 Excel")
    @PreAuthorize("@ss.hasPermission('coupon::export')")
    public void exportExcel(@Valid CouponExportReqVO exportReqVO,
              HttpServletResponse response) throws IOException {
        List<CouponDO> list = Service.getList(exportReqVO);
        // 导出 Excel
        List<CouponExcelVO> datas = CouponConvert.INSTANCE.convertList02(list);
        ExcelUtils.write(response, "优惠券.xls", "数据", CouponExcelVO.class, datas);
    }

    @GetMapping("/legacy-audit")
    @PreAuthorize("@ss.hasPermission('coupon::query')")
    public CommonResult<Map<String,Long>> legacyAudit() {return success(marketing.legacyAudit());}
    @GetMapping("/statistics")
    @PreAuthorize("@ss.hasPermission('coupon::query')")
    public CommonResult<Map<String,Long>> statistics(@RequestParam Long id) {return success(marketing.statistics(id));}
    @GetMapping("/operations")
    @PreAuthorize("@ss.hasPermission('coupon::query')")
    public CommonResult<List<Map<String,Object>>> operations(@RequestParam Long id) {
        marketing.requireTemplate(id,false);
        return success(jdbc.queryForList("SELECT kind,actor_id,actor_type,reason,create_time,coupon_user_id FROM yshop_coupon_operation WHERE coupon_id=? ORDER BY create_time DESC LIMIT 100",id));
    }
}
