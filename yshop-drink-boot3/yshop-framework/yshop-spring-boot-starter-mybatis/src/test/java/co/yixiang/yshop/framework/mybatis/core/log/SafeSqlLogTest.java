package co.yixiang.yshop.framework.mybatis.core.log;

import org.apache.ibatis.logging.Log;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SafeSqlLogTest {
    @Test
    void doesNotForwardSqlLiteralsParametersRowsOrExceptionDetails() {
        Capture sink = new Capture();
        SafeSqlLog log = new SafeSqlLog(sink);
        String privateValue = "private-test-value";
        log.debug("==> Preparing: select * from users where phone='" + privateValue + "'");
        log.debug("==> Parameters: " + privateValue + "(String)");
        log.trace("<== Row: " + privateValue);
        log.warn(privateValue);
        log.error(privateValue, new IllegalArgumentException(privateValue));
        log.error(privateValue);
        log.debug(privateValue);
        assertFalse(String.join("\n", sink.messages).contains(privateValue));
        assertTrue(sink.messages.contains("SQL prepared"));
        assertTrue(sink.messages.contains("SQL failure category=IllegalArgumentException"));
        assertTrue(log.isDebugEnabled());
        assertTrue(log.isTraceEnabled());
    }

    @Test
    void retainsOnlyNumericResultCounts() {
        Capture sink = new Capture();
        SafeSqlLog log = new SafeSqlLog(sink);
        log.debug("<==      Total: 12");
        log.debug("<== Total: 12 private-test-value");
        assertEquals(List.of("<==      Total: 12", "SQL event"), sink.messages);
    }

    @Test
    void retainsStaticStatementIdentityWithoutSqlOrValues() {
        Capture sink = new Capture();
        SafeSqlLog log = new SafeSqlLog(sink, "co.yixiang.StoreOrderMapper.selectById");
        log.debug("==> Preparing: select * from orders where secret='private-test-value'");
        log.debug("==> Parameters: private-test-value(String)");
        assertTrue(sink.messages.stream().allMatch(s -> s.startsWith("statement=co.yixiang.StoreOrderMapper.selectById ")));
        assertFalse(String.join("\n", sink.messages).contains("private-test-value"));
        Capture invalid = new Capture();
        new SafeSqlLog(invalid, "invalid name\nprivate-test-value").debug("==> Preparing: private-test-value");
        assertEquals(List.of("SQL prepared"), invalid.messages);
    }

    private static class Capture implements Log {
        final List<String> messages = new ArrayList<>();
        public boolean isDebugEnabled() { return true; }
        public boolean isTraceEnabled() { return true; }
        public void debug(String s) { messages.add(s); }
        public void trace(String s) { messages.add(s); }
        public void warn(String s) { messages.add(s); }
        public void error(String s) { messages.add(s); }
        public void error(String s, Throwable e) { fail("Raw exception must not be forwarded"); }
    }
}
