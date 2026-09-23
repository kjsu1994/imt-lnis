package server.central.config;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import server.central.agent.*;
import server.central.common.ApiExceptionHandler;
import server.central.dtn.*;
import server.central.input.*;
import server.shared.model.LnisModels.*;

/** 스타일 변경으로 HTTP 상태, JSON 포장 및 예외 응답이 바뀌지 않는지 검사한다. */
class ServerResponseContractTest {
    // 실제 Spring MVC와 동일하게 ProblemDetail 확장 필드를 최상위 JSON으로 직렬화한다.
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final InputBufferService inputService = mock(InputBufferService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentCommandService commands = mock(AgentCommandService.class);
    private final DtnService dtnService = mock(DtnService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        mvc = MockMvcBuilders.standaloneSetup(
                new InputController(inputService),
                new AgentController(agentRepository, commands),
                new DtnController(dtnService, objectMapper),
                new DiscoveryController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void retiresAfsSessionsAndPreservesDiscovery() throws Exception
    {
        mvc.perform(get("/lnis/api/v1/sessions/active"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/lnis/api/v1/discovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("lnis-server"))
                .andExpect(jsonPath("$.agentWebSocketPath").value("/lnis/agent/ws"));
    }

    @Test void forwardsAllSelectedTransportModesWithTestType() throws Exception {
        for (String tx : List.of("DTN", "HDTN")) for (String rx : List.of("DTN", "HDTN")) {
            UUID id = UUID.randomUUID(); DtnJob job = new DtnJob(); job.setId(UUID.randomUUID());
            job.setState("PREPARING"); job.setTestType("GNSS_RAW"); job.setSenderMode(tx); job.setReceiverMode(rx);
            when(dtnService.create(id, "sender-1", "receiver-1", null, "GNSS_RAW", tx, rx)).thenReturn(job);
            mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(
                    Map.of("inputId",id,"senderAgentId","sender-1","receiverAgentId","receiver-1","testType","GNSS_RAW","senderMode",tx,"receiverMode",rx))))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.senderMode").value(tx)).andExpect(jsonPath("$.receiverMode").value(rx));
            verify(dtnService).create(id, "sender-1", "receiver-1", null, "GNSS_RAW", tx, rx);
        }
    }

    @Test
    void preservesAgentArrayAndCommandAcknowledgement() throws Exception
    {
        AgentEntity agent = new AgentEntity("receiver-1", AgentRole.RECEIVER, AgentState.READY,
                Instant.parse("2026-09-08T00:00:00Z"), "1.0.0", 1, "Windows", "amd64", List.of(), null);
        when(agentRepository.findAll()).thenReturn(List.of(agent));
        when(agentRepository.find("receiver-1")).thenReturn(Optional.of(agent));
        UUID commandId = UUID.randomUUID();
        when(commands.command(eq("receiver-1"), isNull(), any(), isNull())).thenReturn(commandId);

        mvc.perform(get("/lnis/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value("receiver-1"));
        mvc.perform(get("/lnis/api/v1/agents/receiver-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"));
        mvc.perform(post("/lnis/api/v1/agents/receiver-1/serial-ports/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.commandId").value(commandId.toString()));
    }

    @Test
    void preservesInputBodyDeleteAndProblemResponses() throws Exception
    {
        UUID id = UUID.randomUUID();
        InputBufferEntity input = new InputBufferEntity(id, InputKind.GRAW_UPLOAD, "sample.graw",
                4, 0, 0, 0, null, false, Instant.now(), null);
        when(inputService.create("sample.graw", 4, InputKind.GRAW_UPLOAD)).thenReturn(input);
        mvc.perform(post("/lnis/api/v1/inputs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"sample.graw\",\"size\":4,\"kind\":\"GRAW_UPLOAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputId").value(id.toString()))
                .andExpect(jsonPath("$.fileName").value("sample.graw"));
        mvc.perform(delete("/lnis/api/v1/inputs/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(true));

        when(inputService.get(id)).thenThrow(new IllegalArgumentException("missing input"));
        mvc.perform(get("/lnis/api/v1/inputs/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("missing input"))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        doThrow(new IllegalStateException("busy")).when(inputService).get(id);
        mvc.perform(get("/lnis/api/v1/inputs/" + id))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void preservesDtnAcceptedSummaryAndReportNullFields() throws Exception
    {
        UUID id = UUID.randomUUID(), inputId = UUID.randomUUID();
        DtnJob job = new DtnJob();
        job.setId(id);
        job.setInputId(inputId);
        job.setSenderAgentId("sender-1");
        job.setReceiverAgentId("receiver-1");
        job.setState("PREPARING");
        when(dtnService.create(inputId, "sender-1", "receiver-1")).thenReturn(job);
        when(dtnService.get(id)).thenReturn(job);
        when(dtnService.recent()).thenReturn(List.of(job));
        when(dtnService.configuration()).thenReturn(Map.of("configured", false));

        mvc.perform(post("/lnis/api/v1/dtn/tests").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("inputId", inputId,
                        "senderAgentId", "sender-1", "receiverAgentId", "receiver-1"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.testId").value(id.toString()))
                .andExpect(jsonPath("$.dtnReceived").value(false));
        mvc.perform(get("/lnis/api/v1/dtn/tests"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].testId").value(id.toString()));
        mvc.perform(get("/lnis/api/v1/dtn/tests/" + id + "/report"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"referencePvt\":null")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"receivedPvt\":null")));
        mvc.perform(get("/lnis/api/v1/dtn/config"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        String pvt = """
                [{"week":2400,"towSeconds":100000,"positionValid":true,"velocityValid":true,
                  "ecefMeters":[1,2,3],"velocityMetersPerSecond":[4,5,6],"receiverClockBiasSeconds":0}]
                """;
        job.setReceivedJson("{\"referencePvt\":" + pvt + "}");
        job.setReceiverJson(pvt);
        job.setReceivedAt(Instant.parse("2026-09-16T01:02:00Z"));
        mvc.perform(get("/lnis/api/v1/dtn/tests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dtnReceived").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"receivedAt\":" + objectMapper.writeValueAsString(job.getReceivedAt()))));
        mvc.perform(get("/lnis/api/v1/dtn/tests/" + id + "/report"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comparison.verdict").value("PASS"))
                .andExpect(jsonPath("$.referencePvt[0].ecefMeters[0]").value(1));
        job.setReceiverJson(pvt.replace("[1,2,3]", "[2,2,3]"));
        mvc.perform(get("/lnis/api/v1/dtn/tests/" + id + "/report"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comparison.verdict").value("FAIL"));
        job.setReceivedJson("{}");
        mvc.perform(get("/lnis/api/v1/dtn/tests/" + id + "/report"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comparison").doesNotExist());
    }
}
