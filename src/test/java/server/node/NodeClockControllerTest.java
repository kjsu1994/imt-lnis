package server.node;

import org.junit.jupiter.api.Test;
import server.common.ServiceClock;
import static org.junit.jupiter.api.Assertions.*;

class NodeClockControllerTest {
    @Test
    void readOnlyStatusUsesSystemTimeWithoutGnssOrPeer() {
        var controller = new NodeClockController(new ServiceClock());
        var value = controller.status().clock();
        assertEquals("SYSTEM", value.source());
        assertEquals(value.rawAt(), value.trialAt());
        assertEquals(0, value.offsetSeconds());
        assertNull(value.calibratedAt());

    }
    @Test
    void calibrationEndpointsAreUnavailable() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new NodeClockController(new ServiceClock())).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/lnis/api/v1/node/clock/prepare"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/lnis/api/v1/node/clock/apply"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
}
