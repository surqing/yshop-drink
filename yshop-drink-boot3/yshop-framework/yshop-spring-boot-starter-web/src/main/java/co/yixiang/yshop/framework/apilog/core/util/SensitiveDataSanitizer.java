package co.yixiang.yshop.framework.apilog.core.util;

import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared, fail-closed audit sanitation; never mutates caller-owned request values. */
public final class SensitiveDataSanitizer {
    // JsonUtils.parseTree logs raw input on parse failure: never use it for audits.
    private static final ObjectMapper AUDIT_PARSER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> SENSITIVE_KEYS = Arrays.stream(new String[]{
            "password", "token", "accessToken", "refreshToken", "Authorization", "secret", "appSecret",
            "clientSecret", "sessionKey", "openid", "mobile", "phoneNumber", "code", "loginCode", "phoneCode",
            "encryptedData", "iv", "phone", "userPhone", "customerPhone", "realName", "nickname", "avatar",
            "address", "userAddress", "customerAddress", "keyPrivate", "privateKey", "apiV3Key", "apiKey",
            "keyCertPwd", "certificatePassword", "mchKey", "payKey", "keyPassword", "keyCert", "keyPublic",
            "keystore", "keystorePwd", "keyPrivateCertPwd", "paymentCredentialMasterKey",
            "YSHOP_PAYMENT_CREDENTIAL_MASTER_KEY", "masterKey"
    }).map(SensitiveDataSanitizer::normalize).collect(Collectors.toSet());

    private SensitiveDataSanitizer() { }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    public static String sanitizeMap(Map<String, ?> map, String[] additionalKeys) {
        if (map == null || map.isEmpty()) return null;
        try {
            return sanitizeJson(JsonUtils.toJsonString(map), additionalKeys);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String sanitizeJson(String json, String[] additionalKeys) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = AUDIT_PARSER.readTree(json);
            // Scalar/form bodies cannot be safely treated as named audit fields.
            if (node == null || (!node.isObject() && !node.isArray())) return null;
            Set<String> keys = new HashSet<>(SENSITIVE_KEYS);
            if (additionalKeys != null) {
                Arrays.stream(additionalKeys).filter(java.util.Objects::nonNull)
                        .map(SensitiveDataSanitizer::normalize).forEach(keys::add);
            }
            sanitize(node, keys);
            return JsonUtils.toJsonString(node);
        } catch (Exception ignored) {
            return null; // Invalid/unparseable payloads are never copied into logs.
        }
    }

    private static void sanitize(JsonNode node, Set<String> keys) {
        if (node.isArray()) {
            node.forEach(child -> sanitize(child, keys));
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (keys.contains(normalize(field.getKey()))) fields.remove();
                else sanitize(field.getValue(), keys);
            }
        }
    }

    public static String sanitizeRequest(String path, Map<String, ?> query, String body, String[] additionalKeys) {
        if (ApiLogUtils.isIdentityRequest(path)) return "{}";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", sanitizeMap(query, additionalKeys));
        params.put("body", sanitizeJson(body, additionalKeys));
        return JsonUtils.toJsonString(params);
    }

    /** Preserve exception locations and cause classes, never messages containing request values. */
    public static String safeStackTrace(Throwable error) {
        StringBuilder result = new StringBuilder();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            result.append(cause.getClass().getName()).append('\n');
            for (StackTraceElement frame : cause.getStackTrace()) result.append("  at ").append(frame).append('\n');
        }
        return result.toString();
    }
}
