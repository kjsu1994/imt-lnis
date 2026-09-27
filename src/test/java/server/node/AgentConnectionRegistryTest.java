package server.node;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

class AgentConnectionRegistryTest {
    @Test
    void usesLiveStateWithoutMessagesAndPreservesReplacementIdentity() {
        var registry = new AgentConnectionRegistry();
        var state = new AtomicBoolean(false);
        BooleanSupplier endpoint = state::get;
        registry.registerEndpoint("sender-1", endpoint);
        assertFalse(registry.online("sender-1"));
        state.set(true);
        assertTrue(registry.online("sender-1"));
        assertThrows(
                IllegalStateException.class,
                () -> registry.registerEndpoint("sender-1", () -> true));
        registry.removeEndpoint("sender-1", () -> true);
        assertTrue(registry.online("sender-1"));
        registry.removeEndpoint("sender-1", endpoint);
        assertFalse(registry.online("sender-1"));
    }
}
