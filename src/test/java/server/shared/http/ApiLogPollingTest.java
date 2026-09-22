package server.shared.http;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ApiLogPollingTest {
    @Test void connectionTransitionsAreIndependentOfFailureVisibility() {
        String key = UUID.randomUUID().toString();
        assertTrue(ApiLog.connectionChanged(key, "failed"));
        assertFalse(ApiLog.connectionChanged(key, "failed"));
        assertTrue(ApiLog.connectionChanged(key, "ready"));
        assertFalse(ApiLog.connectionChanged(key, "ready"));
    }

    @Test void infoOnlyQueriesCountBytesWithoutRetainingPayloads() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApiLog.class);
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            var exchange = new ApiLog.Exchange("IN", "GET", "/lnis/api/v1/dtn/tests/id/report", java.util.Map.of(), null, null);
            byte[] body = "{\"state\":\"COMPLETED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.response.add(body, 0, body.length, true);
            assertEquals(body.length, exchange.response.size());
            assertEquals(0, exchange.response.bytes().length);
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test void exceptionAndScreenTextMaskEmbeddedCredentials() {
        String safe = ApiLog.safe("failed url=http://host/path?token=PRIVATE_QUERY&mode=1 password=PRIVATE_PASSWORD \"apiKey\": \"PRIVATE_JSON\" Bearer PRIVATE_AUTH");
        assertFalse(safe.contains("PRIVATE_"));
        assertTrue(safe.contains("mode=1"));
    }
}
