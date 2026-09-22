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
    void delayRequestUsesExplicitModeAndServerStartTime() throws Exception
    {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        DtnService service = mock(DtnService.class);
        DtnJob job = new DtnJob();
        job.setId(UUID.randomUUID());
        when(service.createDelay(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(job);
        var mvc = MockMvcBuilders.standaloneSetup(new DtnController(service, json)).build();
        UUID inputId = UUID.randomUUID();
        var epoch = new server.shared.codec.DtnDelay.Epoch(96, 2400, 100000);
        var body = Map.of("inputId", inputId, "senderAgentId", "sender-1", "receiverAgentId", "receiver-1",
                "comparisonMode", "DELAY", "selectedEpoch", epoch);
        java.time.Instant before = java.time.Instant.now();
        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isAccepted());
        var time = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(service).createDelay(eq(inputId), eq("sender-1"), eq("receiver-1"), isNull(),
                eq("AFS_METADATA"), eq("DTN"), eq("HDTN"), isNull(), eq(epoch), time.capture());
        assertFalse(time.getValue().isBefore(before));
        assertFalse(time.getValue().isAfter(java.time.Instant.now()));
    }

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

    @Test
    void validatesAndPassesHdtnSettingsWithoutChangingLegacyRequests() throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        DtnService service = mock(DtnService.class);
        DtnJob job = new DtnJob(); job.setId(UUID.randomUUID());
        when(service.create(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(job);
        var mvc = MockMvcBuilders.standaloneSetup(new DtnController(service, json)).build();
        var settings = new LinkedHashMap<String, Object>(Map.of(
                "maxNumberOfBundlesInPipeline", 75, "maxSumOfBundleBytesInPipeline", 60000000L,
                "enforceBundlePriority", false, "neighborDepletedStorageDelaySeconds", 0,
                "maxBundleSizeBytes", 10485760L, "storageDeletionPolicy", "DELETE_AFTER_FORWARDING"));
        var body = new LinkedHashMap<String, Object>(Map.of("inputId", UUID.randomUUID(),
                "senderAgentId", "sender-1", "receiverAgentId", "receiver-1", "hdtnConfig", settings));
        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isAccepted());
        var captured = org.mockito.ArgumentCaptor.forClass(server.shared.model.DtnModels.HdtnConfig.class);
        verify(service).create(any(), eq("sender-1"), eq("receiver-1"), isNull(), eq("AFS_METADATA"),
                eq("DTN"), eq("HDTN"), captured.capture());
        assertEquals(json.valueToTree(settings), json.valueToTree(captured.getValue()));
        clearInvocations(service);
        for (String key : List.copyOf(settings.keySet())) {
            Object original = settings.remove(key);
            mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
            settings.put(key, original);
        }
        for (String key : List.of("maxNumberOfBundlesInPipeline", "maxSumOfBundleBytesInPipeline",
                "neighborDepletedStorageDelaySeconds", "maxBundleSizeBytes")) {
            Object original = settings.put(key, -1);
            mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
            settings.put(key, original);
        }
        settings.put("maxBundleSizeBytes", 9007199254740992L);
        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        settings.put("maxBundleSizeBytes", 10485760L);
        for (int invalid : List.of(1399, 1000001)) {
            settings.put("tcpclMaxSegmentSizeBytes", invalid);
            mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        for (int boundary : List.of(20000, 200000)) {
            settings.put("tcpclMaxSegmentSizeBytes", boundary);
            mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body))).andExpect(status().isAccepted());
        }
    }
    @Test
    void rejectsOutOfRangeAndFractionalSettingsButPreservesAllSupportedPolicies() throws Exception {
        var json = new ObjectMapper();
        var service = mock(DtnService.class);
        var job = new DtnJob(); job.setId(UUID.randomUUID());
        when(service.create(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(job);
        var mvc = MockMvcBuilders.standaloneSetup(new DtnController(service, json)).build();
        var settings = new LinkedHashMap<String, Object>(Map.of(
                "maxNumberOfBundlesInPipeline", 50, "maxSumOfBundleBytesInPipeline", 50000000L,
                "maxBundleSizeBytes", 10485760L, "tcpclMaxSegmentSizeBytes", 20000,
                "neighborDepletedStorageDelaySeconds", 10, "enforceBundlePriority", false,
                "storageDeletionPolicy", "DELETE_AFTER_FORWARDING"));
        var body = Map.of("inputId", UUID.randomUUID(), "senderAgentId", "sender-1",
                "receiverAgentId", "receiver-1", "hdtnConfig", settings);
        var bounds = Map.of(
                "maxNumberOfBundlesInPipeline", new long[]{10,10000},
                "maxSumOfBundleBytesInPipeline", new long[]{1048576,2147483648L},
                "maxBundleSizeBytes", new long[]{1048576,104857600},
                "tcpclMaxSegmentSizeBytes", new long[]{20000,200000},
                "neighborDepletedStorageDelaySeconds", new long[]{0,3600},
                "totalStorageCapacityBytes", new long[]{1,9007199254740991L},
                "maxLtpReceiveUdpPacketSizeBytes", new long[]{1,2147483647},
                "acsSendPeriodMilliseconds", new long[]{1,2147483647});
        for (var entry : bounds.entrySet()) {
            String key = entry.getKey(); Object original = settings.get(key);
            long min = entry.getValue()[0], max = entry.getValue()[1];
            for (Object invalid : List.of(min-1, max+1, min+0.5, Long.toString(min), "")) {
                settings.put(key, invalid);
                mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
            }
            for (long valid : new long[]{min,max}) {
                settings.put(key, valid);
                mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body))).andExpect(status().isAccepted());
            }
            if (original == null) settings.remove(key); else settings.put(key, original);
        }
        for (String policy : List.of("DELETE_AFTER_FORWARDING","on_expiration","on_storage_full","never")) {
            settings.put("storageDeletionPolicy",policy);
            mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body))).andExpect(status().isAccepted());
        }
        settings.put("storageDeletionPolicy","RETAIN");
        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        // Reading stored historical settings must not apply new request validation.
        settings.put("tcpclMaxSegmentSizeBytes",300000);
        assertEquals(300000,json.readValue(json.writeValueAsBytes(settings),
                server.shared.model.DtnModels.HdtnConfig.class).getTcpclMaxSegmentSizeBytes());
    }

}
