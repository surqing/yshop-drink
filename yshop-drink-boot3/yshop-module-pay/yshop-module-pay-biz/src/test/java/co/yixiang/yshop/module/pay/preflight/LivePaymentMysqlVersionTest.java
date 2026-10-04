package co.yixiang.yshop.module.pay.preflight;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class LivePaymentMysqlVersionTest {
    @ParameterizedTest
    @ValueSource(
            strings = {"8.0.29", "8.0.46", "8.0.46-0ubuntu0.22.04.2", "8.4.0", "8.1.0-commercial"})
    void supportedMysql(String version) {
        assertTrue(LivePaymentAuditService.supportedMysqlVersion(version));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "8.0.28",
                "8.0.20",
                "5.7.44",
                "9.0.0",
                "8.0.46-MariaDB",
                "10.11.8-MariaDB",
                "MariaDB",
                "malformed",
                "8.0",
                "8.0.29garbage",
                "8.2147483648.0",
                ""
            })
    void unsupportedMysqlFailsBeforeSchemaReads(String version) {
        assertFalse(LivePaymentAuditService.supportedMysqlVersion(version));
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT VERSION()", String.class)).thenReturn(version);
        assertFalse(new LivePaymentAuditService(jdbc).schemaComplete());
        verify(jdbc).queryForObject("SELECT VERSION()", String.class);
        verifyNoMoreInteractions(jdbc);
    }
}
