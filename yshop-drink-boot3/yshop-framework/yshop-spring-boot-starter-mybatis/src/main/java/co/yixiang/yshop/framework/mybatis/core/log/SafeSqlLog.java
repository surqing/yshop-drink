package co.yixiang.yshop.framework.mybatis.core.log;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;

/** SQL diagnostics must never include bound credentials, row values or exception messages. */
public final class SafeSqlLog implements Log {
    private final Log delegate;
    private final String statement;

    public SafeSqlLog(String name) {
        this(new Slf4jImpl(name), name);
    }

    SafeSqlLog(Log delegate) {
        this(delegate, null);
    }

    SafeSqlLog(Log delegate, String name) {
        this.delegate = delegate;
        // MyBatis supplies a static mapper statement name, never SQL values.
        this.statement = name != null && name.matches("[A-Za-z_$][A-Za-z0-9_.$]*")
                ? "statement=" + name + " " : "";
    }

    private String event(String message) { return statement + message; }

    @Override
    public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }

    @Override
    public boolean isTraceEnabled() { return delegate.isTraceEnabled(); }

    @Override
    public void debug(String message) { delegate.debug(event(safeMessage(message))); }

    @Override
    public void trace(String message) { delegate.trace(event(safeMessage(message))); }

    @Override
    public void warn(String message) { delegate.warn(event("SQL warning")); }

    @Override
    public void error(String message) { delegate.error(event("SQL failure")); }

    @Override
    public void error(String message, Throwable error) {
        delegate.error(event("SQL failure category=" + (error == null ? "unknown" : error.getClass().getSimpleName())));
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
