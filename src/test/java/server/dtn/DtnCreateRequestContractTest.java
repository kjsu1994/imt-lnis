package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

class DtnCreateRequestContractTest {
    @Test
    void delayRequestUsesExplicitModeAndServerStartTime() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        DtnService service = mock(DtnService.class);
        DtnJob job = new DtnJob();
        job.setId(UUID.randomUUID());
        when(service.createDelay(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(job);
        var mvc = MockMvcBuilders.standaloneSetup(new DtnController(service, json)).build();
        UUID inputId = UUID.randomUUID();
        var epoch = new server.pvt.DtnDelay.Epoch(96, 2400, 100000);
        var body =
                Map.of(
                        "inputId",
                        inputId,
                        "senderAgentId",
                        "sender-1",
                        "receiverAgentId",
                        "receiver-1",
                        "comparisonMode",
                        "DELAY",
                        "selectedEpoch",
                        epoch);
        java.time.Instant before = java.time.Instant.now();
        mvc.perform(
                        post("/lnis/api/v1/dtn/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(body)))
                .andExpect(status().isAccepted());
        var time = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(service)
                .createDelay(
                        eq(inputId),
                        eq("sender-1"),
                        eq("receiver-1"),
                        isNull(),
                        eq("AFS_METADATA"),
                        eq("DTN"),
                        eq("HDTN"),
                        isNull(),
                        eq(epoch),
                        time.capture());
        assertFalse(time.getValue().isBefore(before));
        assertFalse(time.getValue().isAfter(java.time.Instant.now()));
    }

    @Test
    void validatesAndPassesHdtnSettingsWithoutChangingLegacyRequests() throws Exception {
        ObjectMapper json = new ObjectMapper();
        DtnService service = mock(DtnService.class);
        DtnJob job = new DtnJob();
        job.setId(UUID.randomUUID());
        when(service.createDelay(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(job);
        var mvc = MockMvcBuilders.standaloneSetup(new DtnController(service, json)).build();
        var settings =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "maxNumberOfBundlesInPipeline",
                                75,
                                "maxSumOfBundleBytesInPipeline",
                                60000000L,
                                "enforceBundlePriority",
                                false,
                                "neighborDepletedStorageDelaySeconds",
                                0,
                                "maxBundleSizeBytes",
                                10485760L,
                                "storageDeletionPolicy",
                                "DELETE_AFTER_FORWARDING"));
        var body =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "inputId",
                                UUID.randomUUID(),
                                "senderAgentId",
                                "sender-1",
                                "receiverAgentId",
                                "receiver-1",
                                "hdtnConfig",
                                settings));
        mvc.perform(
                        post("/lnis/api/v1/dtn/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(body)))
                .andExpect(status().isAccepted());
        var captured =
                org.mockito.ArgumentCaptor.forClass(server.common.DtnModels.HdtnConfig.class);
        verify(service)
                .createDelay(
                        any(),
                        eq("sender-1"),
                        eq("receiver-1"),
                        isNull(),
                        eq("AFS_METADATA"),
                        eq("DTN"),
                        eq("HDTN"),
                        captured.capture(), isNull(), any());
        assertEquals(json.valueToTree(settings), json.valueToTree(captured.getValue()));
        clearInvocations(service);
        for (String key : List.copyOf(settings.keySet())) {
            Object original = settings.remove(key);
            mvc.perform(
                            post("/lnis/api/v1/dtn/tests")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest());
            settings.put(key, original);
        }
        for (String key :
                List.of(
                        "maxNumberOfBundlesInPipeline",
                        "maxSumOfBundleBytesInPipeline",
                        "neighborDepletedStorageDelaySeconds",
                        "maxBundleSizeBytes")) {
            Object original = settings.put(key, "invalid_value");
            mvc.perform(
                            post("/lnis/api/v1/dtn/tests")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest());
            settings.put(key, original);
        }
        for (long largeValue : List.of(3000000000L, 9007199254740992L)) {
            settings.put("maxBundleSizeBytes", largeValue);
            mvc.perform(
                            post("/lnis/api/v1/dtn/tests")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsBytes(body)))
                    .andExpect(status().isAccepted());
        }
        settings.put("maxBundleSizeBytes", 10485760L);
        for (int segment : List.of(20000, 200000, 300000)) {
            settings.put("tcpclMaxSegmentSizeBytes", segment);
            mvc.perform(
                            post("/lnis/api/v1/dtn/tests")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsBytes(body)))
                    .andExpect(status().isAccepted());
        }
    }

    @Test
    void rejectsOutOfRangeAndFractionalSettingsButPreservesAllSupportedPolicies() throws Exception {
        var json = new ObjectMapper();
        var service = mock(DtnService.class);
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        when(service.createDelay(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(job);
        var mvc = MockMvcBuilders.standaloneSetup(new DtnController(service, json)).build();
        var settings =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "maxNumberOfBundlesInPipeline",
                                50,
                                "maxSumOfBundleBytesInPipeline",
                                50000000L,
                                "maxBundleSizeBytes",
                                10485760L,
                                "tcpclMaxSegmentSizeBytes",
                                20000,
                                "neighborDepletedStorageDelaySeconds",
                                10,
                                "enforceBundlePriority",
                                false,
                                "storageDeletionPolicy",
                                "DELETE_AFTER_FORWARDING"));
        var body =
                Map.of(
                        "inputId",
                        UUID.randomUUID(),
                        "senderAgentId",
                        "sender-1",
                        "receiverAgentId",
                        "receiver-1",
                        "hdtnConfig",
                        settings);
        for (String key :
                List.of(
                        "maxNumberOfBundlesInPipeline",
                        "maxSumOfBundleBytesInPipeline",
                        "maxBundleSizeBytes",
                        "tcpclMaxSegmentSizeBytes",
                        "neighborDepletedStorageDelaySeconds",
                        "totalStorageCapacityBytes",
                        "maxLtpReceiveUdpPacketSizeBytes",
                        "acsSendPeriodMilliseconds")) {
            Object original = settings.get(key);
            for (Object invalid : List.of("", "invalid-string")) {
                settings.put(key, invalid);
                mvc.perform(
                                post("/lnis/api/v1/dtn/tests")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsBytes(body)))
                        .andExpect(status().isBadRequest());
            }
            for (long valid : List.of(50000L, 50L)) {
                settings.put(key, valid);
                mvc.perform(
                                post("/lnis/api/v1/dtn/tests")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsBytes(body)))
                        .andExpect(status().isAccepted());
            }
            if (original == null) {
                settings.remove(key);
            } else {
                settings.put(key, original);
            }
        }
        for (String policy :
                List.of("DELETE_AFTER_FORWARDING", "on_expiration", "on_storage_full", "never", "on_forward", "on_delivery")) {
            settings.put("storageDeletionPolicy", policy);
            mvc.perform(
                            post("/lnis/api/v1/dtn/tests")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsBytes(body)))
                    .andExpect(status().isAccepted());
        }
        settings.put("storageDeletionPolicy", "RETAIN");
        mvc.perform(
                        post("/lnis/api/v1/dtn/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());
        // Reading stored historical settings must not apply new request validation.
        settings.put("tcpclMaxSegmentSizeBytes", 300000);
        assertEquals(
                300000,
                json.readValue(
                                json.writeValueAsBytes(settings),
                                server.common.DtnModels.HdtnConfig.class)
                        .getTcpclMaxSegmentSizeBytes());
    }
}
