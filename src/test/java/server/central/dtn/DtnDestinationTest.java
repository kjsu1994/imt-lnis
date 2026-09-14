package server.central.dtn;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class DtnDestinationTest {
    @Test
    void choosesExplicitUrlAndPreservesLegacyDefault()
    {
        String configured = "http://192.168.1.100:8080/transfers";
        assertEquals(URI.create(configured), DtnDestination.resolve(null, configured));
        assertEquals(URI.create(configured), DtnDestination.resolve(null, "http://192.168.1.100:8080"));
        assertEquals(URI.create(configured), DtnDestination.resolve("http://192.168.1.100:8080/", ""));
        assertEquals(URI.create(configured), DtnDestination.resolve(" ", configured));
        assertEquals(URI.create("https://adapter.example/dtn?mode=test"),
                DtnDestination.resolve(" https://adapter.example/dtn?mode=test ", configured));
        assertEquals(URI.create(configured), DtnDestination.resolve(configured, ""));
    }

    @Test
    void rejectsUnsupportedOrCredentialBearingUrls()
    {
        for (String value : new String[] {"", "ftp://adapter/path", "file:///etc/passwd", "localhost:8080",
                "http://user:secret@adapter/path", "http://adapter/path#fragment", "http://adapter:0/path",
                "http://adapter:65536/path", "http://adapter/has space", "http://adapter/" + "a".repeat(2048)}) {
            assertThrows(IllegalArgumentException.class, () -> DtnDestination.resolve(value, ""), value);
        }
    }

    @Test
    void neverForwardsConfiguredTokenToAnotherDestination()
    {
        String configured = "https://adapter.example";
        assertTrue(DtnDestination.usesConfiguredToken(URI.create(configured + "/transfers"), configured));
        for (String destination : new String[] {"https://other.example/transfers", "http://adapter.example/transfers",
                "https://adapter.example/other", "https://adapter.example/transfers?next=other"}) {
            assertFalse(DtnDestination.usesConfiguredToken(URI.create(destination), configured));
        }
        assertFalse(DtnDestination.usesConfiguredToken(URI.create(configured + "/transfers"), ""));
    }
}
