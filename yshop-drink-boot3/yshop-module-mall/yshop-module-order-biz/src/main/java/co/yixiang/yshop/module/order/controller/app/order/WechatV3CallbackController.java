package co.yixiang.yshop.module.order.controller.app.order;

import co.yixiang.yshop.module.order.service.payment.v3.WechatV3PaymentService;

import com.wechat.pay.java.core.notification.RequestParam;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/order/notify/wechat-v3")
public class WechatV3CallbackController {
    private final WechatV3PaymentService service;

    public WechatV3CallbackController(WechatV3PaymentService service) {
        this.service = service;
    }

    @PermitAll
    @PostMapping("/{detailsId}")
    public ResponseEntity<?> receive(
            @PathVariable String detailsId, @RequestBody String body, HttpServletRequest request) {
        try {
            if (!detailsId.matches("[A-Za-z0-9_-]{1,32}")
                    || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                return failure(400);
            for (String h :
                    new String[] {
                        "Wechatpay-Serial",
                        "Wechatpay-Timestamp",
                        "Wechatpay-Nonce",
                        "Wechatpay-Signature"
                    })
                if (request.getHeader(h) == null || request.getHeader(h).isBlank())
                    return failure(400);
            long now = java.time.Instant.now().getEpochSecond(),
                    timestamp = Long.parseLong(request.getHeader("Wechatpay-Timestamp"));
            if (timestamp < now - 300 || timestamp > now + 300) return failure(400);
            var input =
                    new RequestParam.Builder()
                            .serialNumber(request.getHeader("Wechatpay-Serial"))
                            .timestamp(request.getHeader("Wechatpay-Timestamp"))
                            .nonce(request.getHeader("Wechatpay-Nonce"))
                            .signature(request.getHeader("Wechatpay-Signature"))
                            .signType(
                                    request.getHeader("Wechatpay-Signature-Type") == null
                                            ? "WECHATPAY2-SHA256-RSA2048"
                                            : request.getHeader("Wechatpay-Signature-Type"))
                            .body(body)
                            .build();
            if (service.callback(detailsId, input).acknowledge())
                return ResponseEntity.noContent().build();
            return failure(503);
        } catch (RuntimeException ignored) {
            return failure(400);
        }
    }

    private static ResponseEntity<?> failure(int status) {
        return ResponseEntity.status(status)
                .body(Map.of("code", "FAIL", "message", "Notification not accepted"));
    }
}
