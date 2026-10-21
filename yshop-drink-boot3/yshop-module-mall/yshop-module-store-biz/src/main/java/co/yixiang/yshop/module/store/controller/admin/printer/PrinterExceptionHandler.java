package co.yixiang.yshop.module.store.controller.admin.printer;

import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.module.store.service.printer.PrinterFailure;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes={PrinterController.class,OrderPrintJobController.class})
public class PrinterExceptionHandler {
    private static final Map<String,String> MESSAGES=Map.ofEntries(
        Map.entry("HEADQUARTERS_ONLY","仅总部获授权的管理员可以操作打印机"),
        Map.entry("PRINTER_NOT_FOUND","打印机不存在"),Map.entry("SHOP_NOT_FOUND","门店不存在"),Map.entry("PRINTER_BUSY","设备操作处理中，请稍后重试"),
        Map.entry("DUPLICATE_DEVICE","设备已存在，请勿重复添加"),Map.entry("UNBIND_FIRST","请先解绑门店"),
        Map.entry("DEAUTHORIZE_FIRST","请先取消设备授权"),Map.entry("IDENTITY_IMMUTABLE","厂商、设备编号和连接类型不能修改"),
        Map.entry("UNSUPPORTED_OPERATION","此设备暂不支持该操作"),Map.entry("INVALID_CONFIGURATION","请检查设备编号和配置"),Map.entry("UNSUPPORTED_PROVIDER","暂不支持该打印服务"),
        Map.entry("PRINTER_DISABLED","打印机已停用"),Map.entry("PRINTER_NOT_AUTHORIZED","请先连接打印服务"),
        Map.entry("REQUEST_CONFLICT","请求编号已用于其他操作"),Map.entry("ENCRYPTION_NOT_CONFIGURED","请配置后端打印机加密密钥"),
        Map.entry("SECRET_UNAVAILABLE","设备配置无法读取，请检查后端加密密钥"),Map.entry("PROVIDER_NOT_CONFIGURED","请先配置易联云应用"),
        Map.entry("PRINT_JOB_NOT_FOUND","打印任务不存在或已超出当前租户范围"),Map.entry("JOB_NOT_REPRINTABLE","当前任务状态不支持人工重打"),
        Map.entry("PRINTER_NOT_BOUND","原打印机已解绑，不能使用其他设备替代历史任务"),Map.entry("DEVICE_NOT_ONLINE","打印机当前未在线"));
    @ExceptionHandler(PrinterFailure.class)
    public CommonResult<?> failure(PrinterFailure ex) {
        return CommonResult.error(ex.getMessage().equals("HEADQUARTERS_ONLY")?403:1008016100,
                MESSAGES.getOrDefault(ex.getMessage(),"打印机操作未完成，请检查服务配置后重试"));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    public CommonResult<?> duplicate() { return CommonResult.error(1008016101,"设备或测试编号已存在，请刷新后重试"); }
}
