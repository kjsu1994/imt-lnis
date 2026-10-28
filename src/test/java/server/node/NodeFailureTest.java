package server.node;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import server.common.AgentProtocol.*;
import server.common.DtnModels;
import server.common.LnisModels.*;
import server.dtn.DtnJob;
import server.dtn.DtnPayloadDigest;
import server.dtn.DtnRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 중복 관리 요청과 부분 준비 실패가 재실행/잠금 누수로 이어지지 않는지 검증한다. */
class NodeFailureTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final NodeProperties properties =
            new NodeProperties(new MockEnvironment().withProperty("lnis.node.role", "receiver"));

    @Test
    void digestIgnoresWhitespaceAndObjectOrderButNotArrayOrder() throws Exception {
        String first = DtnPayloadDigest.sha256(mapper, mapper.readTree("{\"b\":[1,2],\"a\":true}"));
        assertEquals(
                first,
                DtnPayloadDigest.sha256(mapper, mapper.readTree("{ \"a\":true, \"b\":[1,2] }")));
        assertNotEquals(
                first,
                DtnPayloadDigest.sha256(mapper, mapper.readTree("{\"a\":true,\"b\":[2,1]}")));
    }

    @Test
    void duplicateDtnRegistrationPreservesStoredStateAndRejectsChangedDigest() {
        DtnRepository repository = mock(DtnRepository.class);
        AtomicReference<DtnJob> stored = new AtomicReference<>();
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(repository.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            stored.set(call.getArgument(0));
                            return stored.get();
                        });
        NodeDtnService service =
                new NodeDtnService(properties, mock(NodePeerClient.class), repository, mapper);
        NodeDtnRegistration registration = new NodeDtnRegistration();
        registration.setTestId(UUID.randomUUID());
        registration.setSenderAgentId("sender-1");
        registration.setReceiverAgentId("receiver-1");
        registration.setProfile(DtnModels.PROFILE);
        registration.setPayloadSha256("a".repeat(64));
        var clock = new server.common.ServiceClock();
        var initial = clock.stamp();
        service.accept(registration, initial);
        assertEquals(mapper.valueToTree(initial).toString(), stored.get().getReceiverRegistrationClockJson());
        stored.get().setState("CANCELLED");
        assertEquals("CANCELLED", service.accept(registration, clock.stamp()).getState());
        assertEquals(mapper.valueToTree(initial).toString(), stored.get().getReceiverRegistrationClockJson());
        assertNull(stored.get().getSentJson());
        assertNull(stored.get().getReferenceJson());
        verify(repository, times(1)).saveAndFlush(any());
        registration.setPayloadSha256("b".repeat(64));
        assertThrows(RuntimeException.class, () -> service.accept(registration));
        assertEquals("a".repeat(64), stored.get().getExpectedPayloadSha256());
    }

    @Test
    void restartFailsLostInMemoryDtnWorkWithoutResending() {
        DtnRepository repository = mock(DtnRepository.class);
        UUID id = UUID.randomUUID();
        DtnJob preparing = new DtnJob();
        preparing.setState("PREPARING");
        DtnJob calculating = new DtnJob();
        calculating.setState("CALCULATING");
        when(repository.findByStateIn(java.util.List.of("PREPARING", "CALCULATING")))
                .thenReturn(java.util.List.of(preparing, calculating));
        new NodeRecoveryService(repository).recover();
        assertEquals("FAILED", preparing.getState());
        assertEquals("FAILED", calculating.getState());
        verify(repository, times(2)).save(any());
    }

    private NodeDtnRegistration registration(UUID id, String type) {
        var registration = new NodeDtnRegistration();
        registration.setTestId(id);
        registration.setSenderAgentId("sender-1");
        registration.setReceiverAgentId("receiver-1");
        registration.setProfile("IQ_SAMPLE".equals(type) ? "LANS-AFS-IQ-v1" : DtnModels.PROFILE);
        registration.setPayloadSha256("a".repeat(64));
        registration.setTestType(type);
        registration.setSenderMode("DTN");
        registration.setReceiverMode("HDTN");
        return registration;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"GNSS_RAW", "AFS_METADATA", "IQ_SAMPLE"})
    void preRegistrationKeepsTypeAndRouteBeforeAnyExternalBodyArrives(String type) {
        var repository = mock(DtnRepository.class);
        var service = new NodeDtnService(properties, mock(NodePeerClient.class), repository, mapper);
        var request = registration(UUID.randomUUID(), type);
        service.accept(request);
        var saved = org.mockito.ArgumentCaptor.forClass(DtnJob.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals(type, saved.getValue().getTestType());
        assertEquals("DTN", saved.getValue().getSenderMode());
        assertEquals("HDTN", saved.getValue().getReceiverMode());
        assertEquals("WAITING_DTN", saved.getValue().getState());
        assertNull(saved.getValue().getReceivedJson());
        assertNull(saved.getValue().getReceivedAt());
        request.setTestType("unexpected");
        assertThrows(IllegalArgumentException.class, () -> service.accept(request));
    }

    @Test
    void sendsAndExportsOnlyRegistrationMetadataWithTheSamePayloadDigest() throws Exception {
        var sender = new NodeProperties(new MockEnvironment().withProperty("lnis.node.role", "sender"));
        var peer = mock(NodePeerClient.class);
        var status = new NodeDto.StatusResponse();
        status.setOnline(true);
        when(peer.status()).thenReturn(status);
        var repository = mock(DtnRepository.class);
        var service = new NodeDtnService(sender, peer, repository, mapper);
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setTestType("IQ_SAMPLE");
        job.setSenderMode("HDTN");
        job.setReceiverMode("DTN");
        job.setSenderAgentId("sender-1");
        job.setReceiverAgentId("receiver-1");
        job.setSentJson("{\"testType\":\"IQ_SAMPLE\",\"profile\":\"LANS-AFS-IQ-v1\",\"file\":{\"sizeBytes\":2160000000}}");
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        var accepted = new server.dtn.DtnRemoteResult();
        accepted.setTestId(job.getId());
        when(peer.exchange(eq("/lnis/api/v1/node/peer/dtn/tests"), any(), eq(server.dtn.DtnRemoteResult.class), anyInt()))
                .thenReturn(accepted);
        service.register(job);
        var sent = org.mockito.ArgumentCaptor.forClass(NodeDtnRegistration.class);
        verify(peer).exchange(eq("/lnis/api/v1/node/peer/dtn/tests"), sent.capture(), eq(server.dtn.DtnRemoteResult.class), anyInt());
        assertEquals(mapper.valueToTree(sent.getValue()), mapper.valueToTree(service.registration(job.getId())));
        assertEquals("IQ_SAMPLE", sent.getValue().getTestType());
        assertEquals("HDTN", sent.getValue().getSenderMode());
        assertEquals("DTN", sent.getValue().getReceiverMode());
        assertEquals(DtnPayloadDigest.sha256(mapper, mapper.readTree(job.getSentJson())), sent.getValue().getPayloadSha256());
        var metadata = mapper.valueToTree(sent.getValue());
        for (String excluded : java.util.List.of("file", "frames", "grawBase64", "referencePvt")) {
            assertFalse(metadata.has(excluded));
        }
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> new NodeDtnService(properties, peer, repository, mapper).registration(job.getId()));
    }

    @Test
    void legacyRepairChecksIdentityAndDigestAndNeverRecreatesDeletedRecords() {
        var repository = mock(DtnRepository.class);
        var peer = mock(NodePeerClient.class);
        var service = new NodeDtnService(properties, peer, repository, mapper);
        var status = new NodeDto.StatusResponse();
        status.setOnline(true);
        when(peer.status()).thenReturn(status);
        UUID good = UUID.randomUUID(), wrongDigest = UUID.randomUUID(), wrongId = UUID.randomUUID(), deleted = UUID.randomUUID();
        when(repository.findMissingTrialMetadata()).thenReturn(java.util.List.of(good, wrongDigest, wrongId, deleted));
        for (UUID id : java.util.List.of(good, wrongDigest, wrongId)) {
            var job = new DtnJob();
            job.setId(id);
            job.setExpectedPayloadSha256("a".repeat(64));
            job.setSenderAgentId("sender-1");
            job.setReceiverAgentId("receiver-1");
            job.setState("CANCELLED");
            when(repository.findById(id)).thenReturn(Optional.of(job));
        }
        for (UUID id : java.util.List.of(good, wrongDigest, wrongId, deleted)) {
            var response = registration(id.equals(wrongId) ? UUID.randomUUID() : id, "IQ_SAMPLE");
            if (id.equals(wrongDigest)) response.setPayloadSha256("b".repeat(64));
            when(peer.exchange(eq("/lnis/api/v1/node/peer/dtn/tests/" + id + "/registration"), isNull(),
                    eq(NodeDtnRegistration.class), eq(8192))).thenReturn(response);
        }
        service.restoreMissingTrialMetadata();
        verify(repository).fillTrialMetadata(good, "a".repeat(64), "IQ_SAMPLE", "DTN", "HDTN");
        verify(repository, times(1)).fillTrialMetadata(any(), any(), any(), any(), any());
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).save(any());
        assertEquals("CANCELLED", repository.findById(good).orElseThrow().getState());
        doThrow(new IllegalStateException("offline")).when(peer).status();
        clearInvocations(repository, peer);
        service.restoreMissingTrialMetadata();
        verify(repository, never()).fillTrialMetadata(any(), any(), any(), any(), any());
        verify(peer, never()).exchange(anyString(), any(), any(), anyInt());
    }
}
