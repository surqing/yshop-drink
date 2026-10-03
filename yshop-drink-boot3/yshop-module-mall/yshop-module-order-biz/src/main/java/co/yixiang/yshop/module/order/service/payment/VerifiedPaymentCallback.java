package co.yixiang.yshop.module.order.service.payment;

import co.yixiang.yshop.module.pay.config.handlers.*;

import com.egzosn.pay.ali.api.AliPayService;
import com.egzosn.pay.common.api.PayService;
import com.egzosn.pay.spring.boot.core.PayServiceManager;
import com.egzosn.pay.wx.api.WxPayService;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.util.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Avoids SDK payBack() which logs raw callback maps, even at DEBUG. */
@Service
@Slf4j
public class VerifiedPaymentCallback {
    private final PayServiceManager manager;
    private final WxPayMessageHandler wx;
    private final AliPayMessageHandler ali;

    public VerifiedPaymentCallback(
            PayServiceManager manager, WxPayMessageHandler wx, AliPayMessageHandler ali) {
        this.manager = manager;
        this.wx = wx;
        this.ali = ali;
    }

    public String receive(String detailsId, HttpServletRequest request) {
        PayService service = null;
        try {
            if (detailsId == null || !detailsId.matches("[A-Za-z0-9_-]{1,32}"))
                throw new IllegalArgumentException("INVALID_MERCHANT_ID");
            service =
                    manager.cast(
                            detailsId, PayService.class); // Server-side decrypt/config binding.
            if (service instanceof WxPayService wxService) {
                byte[] bytes = request.getInputStream().readNBytes(16385);
                if (bytes.length > 16384) throw new IllegalArgumentException("CALLBACK_TOO_LARGE");
                return wx.handleCallback(detailsId, parseXml(bytes), wxService).toMessage();
            }
            if (service instanceof AliPayService aliService) {
                Map<String, Object> body = new TreeMap<>();
                if (request.getParameterMap().size() > 64)
                    throw new IllegalArgumentException("CALLBACK_TOO_LARGE");
                for (var entry : request.getParameterMap().entrySet()) {
                    if (entry.getValue().length != 1 || entry.getValue()[0].length() > 4096)
                        throw new IllegalArgumentException("INVALID_CALLBACK_FIELD");
                    body.put(entry.getKey(), entry.getValue()[0]);
                }
                return ali.handleCallback(detailsId, body, aliService).toMessage();
            }
        } catch (Exception failure) {
            // Exception messages/stack traces may contain keys or input: category only.
            log.warn("payment callback rejected category={}", failure.getClass().getSimpleName());
        }
        if (service instanceof AliPayService)
            return service.getPayOutMessage("fail", "Retry later").toMessage();
        return "<xml><return_code><![CDATA[FAIL]]></return_code><return_msg><![CDATA[Retry"
                + " later]]></return_msg></xml>";
    }

    static Map<String, Object> parseXml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        var root =
                builder.parse(new InputSource(new ByteArrayInputStream(bytes)))
                        .getDocumentElement();
        if (!"xml".equals(root.getNodeName()))
            throw new IllegalArgumentException("INVALID_CALLBACK_XML");
        Map<String, Object> body = new TreeMap<>();
        var nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            if (node.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            if (node.getChildNodes().getLength() > 1
                    || body.putIfAbsent(node.getNodeName(), node.getTextContent()) != null)
                throw new IllegalArgumentException("INVALID_CALLBACK_FIELD");
        }
        return body;
    }
}
