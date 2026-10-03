package co.yixiang.yshop.framework.mybatis.core.log;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;

/** SQL diagnostics must never include bound credentials, row values or exception messages. */
public final class SafeSqlLog implements Log {
    private final Log delegate;

    public SafeSqlLog(String name) {
        this(new Slf4jImpl(name));
    }

    SafeSqlLog(Log delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }

    @Override
    public boolean isTraceEnabled() { return delegate.isTraceEnabled(); }

    @Override
    public void debug(String message) { delegate.debug(safeMessage(message)); }

    @Override
    public void trace(String message) { delegate.trace(safeMessage(message)); }

    @Override
    public void warn(String message) { delegate.warn("SQL warning"); }

    @Override
    public void error(String message) { delegate.error("SQL failure"); }

    @Override
    public void error(String message, Throwable error) {
        delegate.error("SQL failure category=" + (error == null ? "unknown" : error.getClass().getSimpleName()));
    }

    private static String safeMessage(String message) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.matches("<==\\s+Total:\\s*\\d+")) return trimmed;
        if (trimmed.startsWith("==> Preparing:")) return "SQL prepared";
        if (trimmed.startsWith("==> Parameters:")) return "SQL parameters omitted";
        if (trimmed.startsWith("<== Row:") || trimmed.startsWith("<== Columns:")) return "SQL result values omitted";
        return "SQL event";
    }
}
