package server.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RunModeTest {
    @Test
    void nodeEnablesWebAndLocalProfileWithoutStartingLegacyAgentProfile() {
        RunMode.Selection selection = RunMode.select(new String[] {"node"});
        assertEquals(RunMode.NODE, selection.mode());
        assertTrue(selection.mode().webEnabled());
        assertArrayEquals(new String[] {"server", "node"}, selection.mode().profiles());
        for (String removed : new String[] {"server", "sender", "receiver"}) {
            assertThrows(
                    IllegalArgumentException.class, () -> RunMode.select(new String[] {removed}));
        }
    }

    @Test
    void separatesModeFromSpringArguments() {
        var selection = RunMode.select(new String[] {"node", "--lnis.agent.id=sender-a"});

        assertEquals(RunMode.NODE, selection.mode());
        assertArrayEquals(new String[] {"--lnis.agent.id=sender-a"}, selection.springArguments());
    }

    @Test
    void rejectsMissingOrUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> RunMode.select(new String[0]));
        assertThrows(
                IllegalArgumentException.class, () -> RunMode.select(new String[] {"unknown"}));
    }
}
