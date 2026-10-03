package co.yixiang.yshop.framework.apilog.core.util;

/** Identifies routes whose failures can contain credentials or personal data. */
public final class ApiLogUtils {
    private ApiLogUtils() {
    }

    public static boolean isIdentityRequest(String path) {
        return path != null && (path.contains("/auth/") || path.contains("/member/")
                || path.contains("/system/user/") || path.contains("/notify/payBack"));
    }
}
