package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DtnCreateRequestContractTest {
    @Test
    void preservesMissingBlankNullAndExplicitRequestFields() throws Exception
    {
        UUID inputId = UUID.randomUUID();
        UUID iqId = UUID.randomUUID();
        ObjectMapper json = new ObjectMapper();
        List<Object[]> calls = new ArrayList<>();
        DtnJob job = new DtnJob();
        job.setId(UUID.randomUUID());
        DtnService service = mock(DtnService.class, invocation -> {
            if (invocation.getMethod().getName().equals("create")) {
                calls.add(invocation.getArguments());
                return job;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        var mvc = MockMvcBuilders.standaloneSetup(new DtnController(service, json)).build();
        var request = new LinkedHashMap<String, Object>();
        request.put("inputId", inputId);
        request.put("iqFileId", iqId);
        request.put("senderAgentId", "sender-1");
        request.put("receiverAgentId", "receiver-1");
        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isAccepted());
        assertArrayEquals(new Object[] {inputId, "sender-1", "receiver-1"}, calls.removeFirst());
        request.put("sendUrl", " ");
        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isAccepted());
        assertEquals(3, calls.removeFirst().length);
        request.put("sendUrl", "http://adapter:8080/custom?route=1");
        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isAccepted());
        assertArrayEquals(new Object[] {inputId, "sender-1", "receiver-1", request.get("sendUrl")}, calls.removeFirst());
        for (String type : Arrays.asList("GNSS_RAW", "IQ_SAMPLE", null)) {
            request.put("testType", type);
            mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(request))).andExpect(status().isAccepted());
            assertArrayEquals(new Object[] {"IQ_SAMPLE".equals(type) ? iqId : inputId,
                    "sender-1", "receiver-1", request.get("sendUrl"), type}, calls.removeFirst());
        }
        request.put("testType", "IQ_SAMPLE");
        request.put("senderMode", "DTN");
        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isAccepted());
        // A partially specified mode is passed through for the existing service validation.
        assertArrayEquals(new Object[] {iqId, "sender-1", "receiver-1", request.get("sendUrl"),
                "IQ_SAMPLE", "DTN", null}, calls.removeFirst());
    }
}
