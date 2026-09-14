package server.central.dtn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import server.central.agent.AgentCommandService;
import server.central.agent.AgentConnectionRegistry;
import server.central.agent.AgentRepository;
import server.central.input.InputBufferService;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 수신 원문의 공백·한글·줄바꿈과 기존 중복/인증/동일성 검증 계약을 함께 검사한다. */
class DtnPayloadTest {
    @Test
    void observationViewPreservesNavigationAndRoundTripsAsJson() throws Exception {
        var navigation = new server.shared.codec.GrawCodec.NavigationUpdate(0, 19, 0, 0, 2,
                java.util.List.of(0L, 4294967295L));
        var record = server.shared.codec.GrawCodec.encode(new server.shared.codec.GrawCodec.Envelope(
                UUID.randomUUID(), UUID.randomUUID(), 7, java.time.Instant.parse("2026-09-14T00:00:00Z"), navigation));
        var view = server.shared.model.DtnObservationView.fromRecords(java.util.List.of(record, record));
        assertEquals(2, view.navigationCount());
        assertEquals(2, view.navigation().size());
        assertEquals(navigation.words(), view.navigation().getFirst().message().words());
        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.writeValueAsString(view);
        var restored = mapper.readValue(json, server.shared.model.DtnObservationView.class);
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(restored)));
        assertEquals(2, restored.records().size());
        assertTrue(server.shared.model.DtnObservationView.fromRecords(java.util.List.of()).navigation().isEmpty());
    }
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DtnRepository repository = mock(DtnRepository.class);
    private final DtnService service = new DtnService(repository, mock(AgentCommandService.class),
            mock(AgentRepository.class), mock(AgentConnectionRegistry.class),
            mock(InputBufferService.class), objectMapper);
    private final DtnJob job = new DtnJob();
    private String original;

    @BeforeEach
    void setUp() throws Exception
    {
        job.setId(UUID.randomUUID());
        job.setState("WAITING_DTN");
        original = "{\r\n  \"testId\": \"" + job.getId() + "\",\r\n  \"note\": \"한글 원문\"\r\n}\r\n";
        job.setSentJson(objectMapper.writeValueAsString(objectMapper.readTree(original)));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        ReflectionTestUtils.setField(service, "receiveToken", "test-token");
    }

    @Test
    void preservesFirstAcceptedBodyAndDoesNotOverwriteItOnDuplicate() throws Exception
    {
        service.receive("Bearer test-token", original.getBytes(StandardCharsets.UTF_8));
        assertEquals(original, job.getReceivedRawJson());
        assertEquals(objectMapper.readTree(original), objectMapper.readTree(job.getReceivedJson()));
        service.receive("Bearer test-token", job.getSentJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(original, job.getReceivedRawJson());
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), service.payload(job.getId(), "received").getBody());
        assertEquals("original", service.payload(job.getId(), "received").getRepresentation());
        assertArrayEquals(job.getSentJson().getBytes(StandardCharsets.UTF_8), service.payload(job.getId(), "sent").getBody());
    }

    @Test
    void invalidRequestsDoNotSaveBody() throws Exception
    {
        assertThrows(ResponseStatusException.class,
                () -> service.receive("Bearer wrong-token", original.getBytes(StandardCharsets.UTF_8)));
        String changed = original.replace("한글 원문", "변경된 값");
        assertThrows(IllegalArgumentException.class,
                () -> service.receive("Bearer test-token", changed.getBytes(StandardCharsets.UTF_8)));
        assertNull(job.getReceivedRawJson());
        verify(repository, never()).save(any());
    }

    @Test
    void identifiesLegacyRepresentationAndUnavailablePayload()
    {
        assertThrows(ResponseStatusException.class, () -> service.payload(job.getId(), "received"));
        assertThrows(IllegalArgumentException.class, () -> service.payload(job.getId(), "unknown"));
        job.setReceivedJson(job.getSentJson());
        assertEquals("legacy-normalized", service.payload(job.getId(), "received").getRepresentation());
    }

    @Test
    void downloadReturnsOriginalBytesWithoutAdditionalJsonWrapping() throws Exception
    {
        service.receive("Bearer test-token", original.getBytes(StandardCharsets.UTF_8));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DtnController(service, objectMapper))
                .setMessageConverters(new ByteArrayHttpMessageConverter()).build();
        mvc.perform(get("/lnis/api/v1/dtn/tests/" + job.getId() + "/payload/received?download=true"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-LNIS-Payload-Representation", "original"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"dtn-" + job.getId() + "-received.json\""))
                .andExpect(content().bytes(original.getBytes(StandardCharsets.UTF_8)));
    }
}
