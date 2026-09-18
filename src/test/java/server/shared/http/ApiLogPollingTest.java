package server.shared.http;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ApiLogPollingTest {
    @Test void repeatedHealthFailureReportsOncePerMinuteAndRecoveryImmediately() {
        String key=UUID.randomUUID().toString();
        assertTrue(ApiLog.observe(key,"failed",true,0).report());
        assertFalse(ApiLog.observe(key,"failed",true,10_000_000_000L).report());
        var reminder=ApiLog.observe(key,"failed",true,60_000_000_000L);
        assertTrue(reminder.report());assertFalse(reminder.changed());assertEquals(1,reminder.suppressed());
        assertTrue(ApiLog.observe(key,"ready",true,61_000_000_000L).report());
    }
}
