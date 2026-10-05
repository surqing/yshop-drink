package co.yixiang.yshop.module.pay.preflight;

import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;
import co.yixiang.yshop.module.pay.credential.PaymentCredentialCryptoService;
import co.yixiang.yshop.module.pay.dal.mysql.merchantdetails.MerchantDetailsMapper;

import com.wechat.pay.java.core.RSAPublicKeyConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.*;
import java.util.*;

/** Offline diagnostics only. Does not instantiate a payment client or SDK HTTP transport. */
@Service
public class LivePaymentPreflightService {
    private final MerchantDetailsMapper merchants;
    private final PaymentCredentialCryptoService crypto;
    private final LivePaymentAuditService audit;

    @Value("${yshop.pay.wechat-v3.enabled:false}")
    private boolean live;

    @Value("${yshop.pay.wechat-v3.reconciliation-enabled:false}")
    private boolean recovery;

    @Value("${yshop.pay.preflight.ingress-verified:false}")
    private boolean ingressVerified;

    @Value("${yshop.pay.preflight.history-reviewed:false}")
    private boolean historyReviewed;

    @Value("${yshop.pay.preflight.clock.observed-at:}")
    private String clockObservedAt = "";

    @Value("${yshop.pay.preflight.clock.ntp-offset-millis:}")
    private String ntpOffset = "";

    @Value("${yshop.pay.preflight.clock.evidence-file:}")
    private String clockEvidenceFile = "";

    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LivePaymentPreflightService(
            MerchantDetailsMapper merchants,
            PaymentCredentialCryptoService crypto,
            LivePaymentAuditService audit) {
        this(merchants, crypto, audit, Clock.systemUTC());
    }

    LivePaymentPreflightService(
            MerchantDetailsMapper merchants,
            PaymentCredentialCryptoService crypto,
            LivePaymentAuditService audit,
            Clock clock) {
        this.merchants = merchants;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    public record ClockDiagnostic(
            Instant serverUtc,
            Long databaseOffsetMillis,
            Long ntpOffsetMillis,
            boolean freshNtpEvidence) {}

    public record Report(
            boolean configurationReady,
            boolean liveReady,
            Map<String, Boolean> checks,
            LivePaymentAuditService.Snapshot audit,
            ClockDiagnostic clock) {}

    @TenantIgnore
    public Report check(String detailsId) {
        var checks = new TreeMap<String, Boolean>();
        for (String name :
                List.of(
                        "MERCHANT_V3",
                        "IDENTITY",
                        "CERTIFICATE_SERIAL",
                        "PLATFORM_PUBLIC_KEY",
                        "PRIVATE_KEY_ENCRYPTED",
                        "API_V3_KEY_ENCRYPTED",
                        "MASTER_KEY_DECRYPTION",
                        "OFFLINE_SDK_CONFIG",
                        "NOTIFY_URL")) checks.put(name, false);
        try {
            if (detailsId == null || !detailsId.matches("[A-Za-z0-9_-]{1,32}"))
                throw new IllegalStateException();
            var m = merchants.selectById(detailsId);
            if (m != null) {
                checks.put(
                        "MERCHANT_V3",
                        !Boolean.TRUE.equals(m.getDeleted())
                                && "wxPay".equals(m.getPayType())
                                && "V3".equals(m.getWechatApiVersion())
                                && Integer.valueOf(0).equals(m.getIsTest()));
                checks.put("IDENTITY", present(m.getAppid()) && present(m.getMchId()));
                checks.put("CERTIFICATE_SERIAL", present(m.getMerchantCertificateSerial()));
                checks.put(
                        "PLATFORM_PUBLIC_KEY",
                        present(m.getPlatformPublicKeyId()) && present(m.getKeyPublic()));
                checks.put("PRIVATE_KEY_ENCRYPTED", envelope(m.getKeyPrivate()));
                checks.put("API_V3_KEY_ENCRYPTED", envelope(m.getApiV3Key()));
                checks.put("NOTIFY_URL", validNotify(m.getNotifyUrl(), detailsId));
                String privateKey = crypto.decrypt(detailsId, "keyPrivate", m.getKeyPrivate());
                String apiKey = crypto.decrypt(detailsId, "apiV3Key", m.getApiV3Key());
                boolean valid =
                        present(privateKey)
                                && apiKey != null
                                && apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                                        == 32;
                checks.put("MASTER_KEY_DECRYPTION", valid);
                if (valid
                        && Boolean.TRUE.equals(checks.get("PLATFORM_PUBLIC_KEY"))
                        && Boolean.TRUE.equals(checks.get("CERTIFICATE_SERIAL"))
                        && Boolean.TRUE.equals(checks.get("IDENTITY"))) {
                    // Config parses keys and prepares crypto only. No certificate downloader,
                    // DefaultHttpClient, JsapiService, factory or provider request is constructed.
                    new RSAPublicKeyConfig.Builder()
                            .merchantId(m.getMchId())
                            .merchantSerialNumber(m.getMerchantCertificateSerial())
                            .privateKey(privateKey)
                            .publicKey(m.getKeyPublic())
                            .publicKeyId(m.getPlatformPublicKeyId())
                            .apiV3Key(apiKey)
                            .build();
                    checks.put("OFFLINE_SDK_CONFIG", true);
                }
            }
        } catch (RuntimeException ignored) {
            /* Safe fixed codes only; never return inputs/causes. */
        }
        checks.put("DATABASE_SCHEMA", audit.schemaComplete());
        var snapshot = audit.snapshot();
        checks.put("AUDIT_AVAILABLE", snapshot.available());
        Instant now = clock.instant();
        Long offset = null;
        boolean fresh = false;
        try {
            String observedAt = clockObservedAt, measuredOffset = ntpOffset;
            if (present(clockEvidenceFile)) {
                var path = java.nio.file.Path.of(clockEvidenceFile);
                if (java.nio.file.Files.isSymbolicLink(path)
                        || !java.nio.file.Files.isRegularFile(path)
                        || java.nio.file.Files.size(path) > 4096
                        || java.nio.file.Files.getPosixFilePermissions(path).stream()
                                .anyMatch(
                                        p ->
                                                p.name().startsWith("GROUP_")
                                                        || p.name().startsWith("OTHERS_")))
                    throw new IllegalStateException();
                var p = new java.util.Properties();
                try (var in = java.nio.file.Files.newInputStream(path)) {
                    p.load(in);
                }
                observedAt = p.getProperty("yshop.pay.preflight.clock.observed-at", "");
                measuredOffset = p.getProperty("yshop.pay.preflight.clock.ntp-offset-millis", "");
            }
            Instant observed = Instant.parse(observedAt);
            long age = Duration.between(observed, now).getSeconds();
            offset = Long.valueOf(measuredOffset);
            fresh = age >= 0 && age <= 300;
        } catch (RuntimeException | java.io.IOException ignored) {
            /* Unknown NTP is not proof of synchronization. */
        }
        Long databaseOffset = audit.databaseOffsetMillis(now);
        var timing = new ClockDiagnostic(now, databaseOffset, offset, fresh);
        boolean configured = checks.values().stream().allMatch(Boolean.TRUE::equals);
        checks.put("CONFIGURATION_RELATIONSHIP", !recovery || live);
        checks.put("LEGACY_EXTERNAL_LIVE_GATE", live);
        checks.put("RECONCILIATION_ENABLED", recovery);
        checks.put("NO_BLOCKING_PAYMENT_RISK", !snapshot.hasBlockingRisk());
        checks.put("CALLBACK_INGRESS_VERIFIED", ingressVerified);
        checks.put("HISTORY_REVIEWED", historyReviewed);
        checks.put(
                "CLOCK_SYNCHRONIZED",
                fresh
                        && offset != null
                        && offset >= -1000
                        && offset <= 1000
                        && databaseOffset != null
                        && databaseOffset >= -5000
                        && databaseOffset <= 5000);
        return new Report(
                configured,
                checks.values().stream().allMatch(Boolean.TRUE::equals),
                Collections.unmodifiableMap(checks),
                snapshot,
                timing);
    }

    public record Deployment(
            String buildRevision,
            String mysqlVersion,
            boolean schemaComplete,
            boolean liveEnabled,
            boolean reconciliationEnabled,
            String callbackRoute,
            boolean masterKeyAvailable,
            boolean merchantConfigurationReady,
            String merchantFingerprint,
            LivePaymentAuditService.Snapshot audit) {}

    @TenantIgnore
    public Deployment deployment(String detailsId) {
        String revision = "UNVERIFIED", fingerprint = "UNAVAILABLE";
        boolean master = false;
        try {
            crypto.requireKey();
            master = true;
        } catch (RuntimeException ignored) {
        }
        try (var in = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
            var p = new java.util.Properties();
            if (in != null) {
                p.load(in);
                String value = p.getProperty("build.sourceRevision", "");
                if (value.matches("[a-f0-9]{40}")) revision = value;
            }
        } catch (java.io.IOException ignored) {
        }
        try {
            if (detailsId != null && detailsId.matches("[A-Za-z0-9_-]{1,32}")) {
                var m = merchants.selectById(detailsId);
                if (m != null) {
                    var fields =
                            new String[] {
                                detailsId,
                                m.getPayType(),
                                m.getWechatApiVersion(),
                                m.getAppid(),
                                m.getMchId(),
                                m.getMerchantCertificateSerial(),
                                m.getPlatformPublicKeyId(),
                                m.getKeyPublic(),
                                m.getNotifyUrl()
                            };
                    // Length-prefixed metadata and public material only. Never hash a
                    // secret/envelope.
                    var bytes = new java.io.ByteArrayOutputStream();
                    var out = new java.io.DataOutputStream(bytes);
                    for (String value : fields) {
                        byte[] b =
                                String.valueOf(value)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        out.writeInt(b.length);
                        out.write(b);
                    }
                    fingerprint =
                            java.util.HexFormat.of()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("SHA-256")
                                                    .digest(bytes.toByteArray()));
                }
            }
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ignored) {
        }
        return new Deployment(
                revision,
                audit.mysqlVersion(),
                audit.schemaComplete(),
                live,
                recovery,
                "/app-api/order/notify/wechat-v3/{detailsId}",
                master,
                check(detailsId).configurationReady(),
                fingerprint,
                audit.snapshot());
    }

    private static boolean present(String s) {
        return !PaymentCredentialCryptoService.blank(s);
    }

    private static boolean envelope(String s) {
        return s != null && s.startsWith(PaymentCredentialCryptoService.PREFIX);
    }

    static boolean validNotify(String value, String id) {
        try {
            URI u = URI.create(value);
            return "https".equals(u.getScheme())
                    && u.getHost() != null
                    && !u.getHost().equalsIgnoreCase("localhost")
                    && !u.getHost().equals("127.0.0.1")
                    && !u.getHost().equals("[::1]")
                    && u.getRawUserInfo() == null
                    && u.getRawQuery() == null
                    && u.getRawFragment() == null
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && ("/app-api/order/notify/wechat-v3/" + id).equals(u.getRawPath());
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
