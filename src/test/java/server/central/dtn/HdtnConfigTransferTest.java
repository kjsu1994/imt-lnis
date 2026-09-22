package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import server.central.agent.*;
import server.central.input.*;
import server.shared.model.AgentProtocol.*;
import server.shared.model.DtnModels.*;
import server.shared.model.LnisModels.*;

class HdtnConfigTransferTest {
    @ParameterizedTest
    @CsvSource({"AFS_METADATA,true", "GNSS_RAW,true", "IQ_SAMPLE,true",
            "AFS_METADATA,false", "GNSS_RAW,false", "IQ_SAMPLE,false"})
    @Timeout(20)
    void sendsImmutableSettingsForEveryPayloadAndPreservesLegacyJson(String type, boolean configured) throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        BlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        HttpServer adapter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        adapter.createContext("/transfers", exchange -> {
            try (exchange) {
                delivered.add(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(202, -1);
            }
        });
        adapter.start();
        CountDownLatch prepareIq = new CountDownLatch(1);
        try {
            var repository = mock(DtnRepository.class);
            var commands = mock(AgentCommandService.class);
            var agents = mock(AgentRepository.class);
            var connections = mock(AgentConnectionRegistry.class);
            var inputs = mock(InputBufferService.class);
            DtnService service = new DtnService(repository, commands, agents, connections, inputs, json);
            Map<UUID, DtnJob> jobs = new ConcurrentHashMap<>();
            when(repository.save(any())).thenAnswer(call -> { DtnJob job = call.getArgument(0); jobs.put(job.getId(), job); return job; });
            when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(jobs.get(call.getArgument(0))));
            for (AgentRole role : List.of(AgentRole.SENDER, AgentRole.RECEIVER)) {
                String id = role == AgentRole.SENDER ? "sender-1" : "receiver-1";
                AgentEntity agent = mock(AgentEntity.class);
                when(agent.role()).thenReturn(role); when(agent.state()).thenReturn(AgentState.READY);
                when(agents.find(id)).thenReturn(Optional.of(agent)); when(connections.online(id)).thenReturn(true);
            }
            String url = "http://127.0.0.1:" + adapter.getAddress().getPort();
            ReflectionTestUtils.setField(service, "sendUrl", url);
            ReflectionTestUtils.setField(service, "sendToken", "");
            ReflectionTestUtils.setField(service, "receiveToken", "test-token");
            UUID inputId = UUID.randomUUID();
            InputBufferEntity input = mock(InputBufferEntity.class);
            when(input.complete()).thenReturn(true); when(input.receivedSize()).thenReturn(1L);
            when(input.chunkCount()).thenReturn(1L);
            when(inputs.get(inputId)).thenReturn(input); when(inputs.chunk(inputId, 0)).thenReturn(new byte[]{1});
            IqService iq = mock(IqService.class);
            when(iq.completed(inputId)).thenAnswer(call -> {
                assertTrue(prepareIq.await(5, TimeUnit.SECONDS));
                return new IqFile("/exchange/" + inputId + ".bin", 100, "digest", 90, 12000000, "IQ_INTERLEAVED_INT8", 2);
            });
            ReflectionTestUtils.setField(service, "iq", iq);
            HdtnConfig settings = new HdtnConfig();
            settings.setMaxNumberOfBundlesInPipeline(75); settings.setMaxSumOfBundleBytesInPipeline(60000000L);
            settings.setEnforceBundlePriority(false); settings.setNeighborDepletedStorageDelaySeconds(0);
            settings.setTcpclMaxSegmentSizeBytes(100000);
            settings.setTotalStorageCapacityBytes(8589934592L);
            settings.setMaxLtpReceiveUdpPacketSizeBytes(65536);
            settings.setAcsSendPeriodMilliseconds(1000);
            settings.setMaxBundleSizeBytes(10485760L); settings.setStorageDeletionPolicy("DELETE_AFTER_FORWARDING");
            JsonNode expected = json.readTree(json.writeValueAsBytes(settings));
            DtnJob job = configured
                    ? service.create(inputId, "sender-1", "receiver-1", url, type, "DTN", "HDTN", settings)
                    : service.create(inputId, "sender-1", "receiver-1", url, type, "DTN", "HDTN");
            settings.setMaxNumberOfBundlesInPipeline(999);
            prepareIq.countDown();
            if (!"IQ_SAMPLE".equals(type)) {
                Transfer transfer = new Transfer(); transfer.setTestId(job.getId()); transfer.setTestType(type);
                AgentResult result = new AgentResult(); result.setTransfer(transfer); result.setPvt(List.of(new Pvt()));
                var chunk = json.valueToTree(Map.of("index", 0, "last", true,
                        "dataBase64", Base64.getEncoder().encodeToString(json.writeValueAsBytes(result))));
                service.agentData(Envelope.of(MessageType.DTN_DATA, "sender-1", AgentRole.SENDER, job.getId(), chunk));
            }
            byte[] body = delivered.poll(5, TimeUnit.SECONDS);
            assertNotNull(body, job.getMessage());
            JsonNode wire = json.readTree(body);
            assertEquals(type, wire.path("testType").asText());
            assertFalse(wire.has("dtnConfig"));
            if (configured) {
                assertEquals(expected, wire.get("hdtnConfig"));
                assertEquals(expected, json.readTree(job.getHdtnConfigJson()));
            } else {
                assertFalse(wire.has("hdtnConfig"));
                assertNull(job.getHdtnConfigJson());
            }
            assertArrayEquals(body, service.payload(job.getId(), "sent").getBody());
            service.receive("Bearer test-token", body);
            assertArrayEquals(body, service.payload(job.getId(), "received").getBody());
        } finally { prepareIq.countDown(); adapter.stop(0); }
    }

    @Test
    void rejectsHdtnConfigurationOnDtnOnlyRoute() {
        DtnService service = new DtnService(mock(DtnRepository.class), mock(AgentCommandService.class),
                mock(AgentRepository.class), mock(AgentConnectionRegistry.class), mock(InputBufferService.class), new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(), "sender-1", "receiver-1",
                "http://adapter:8080", "GNSS_RAW", "DTN", "DTN", new HdtnConfig()));
    }
}
