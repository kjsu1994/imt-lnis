package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import server.agent.codec.*;
import server.agent.dtn.DtnProcessor;
import server.central.agent.AgentEntity;
import server.central.agent.AgentRepository;
import server.central.agent.AgentCommandService;
import server.central.agent.AgentConnectionRegistry;
import server.central.input.InputBufferService;
import server.shared.codec.DtnChunks;
import server.shared.model.AgentProtocol.*;
import server.shared.model.DtnModels;
import server.shared.model.LnisModels.*;

/** 실제 HTTP callback, H2 저장, 네이티브 AFS/PVT 왕복을 검증한다. 외부 서버는 DTN 전송 대신 JSON만 돌려준다. */
@EnabledOnOs(OS.WINDOWS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:dtn-flow;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "lnis.storage.data-directory=${java.io.tmpdir}/lnis-dtn-flow-tests",
    "lnis.storage.cleanup-delay=PT24H", "lnis.dtn.receive-token=test-receive-token"})
@ActiveProfiles("server")
class DtnFlowIntegrationTest {
  static final AtomicReference<URI> callback = new AtomicReference<>();
  static final AtomicReference<byte[]> packet = new AtomicReference<>();
  static final AtomicReference<String> destinationPath = new AtomicReference<>();
  static final AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
  static final HttpServer external;
  static {
    try {
      external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      external.createContext("/transfers", exchange -> {
        destinationPath.set(exchange.getRequestURI().getPath());
        forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = exchange.getRequestBody().readAllBytes(); packet.set(body);
        try {
          var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(callback.get())
              .header("Authorization", "Bearer test-receive-token").header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding());
          exchange.sendResponseHeaders(response.statusCode() == 202 ? 202 : 500, -1);
        } catch (Exception e) { exchange.sendResponseHeaders(500, -1); }
        finally { exchange.close(); }
      });
      external.start();
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("lnis.dtn.send-token", () -> "configured-secret-not-for-custom-url");
    registry.add("lnis.dtn.send-url", () -> "http://127.0.0.1:" + external.getAddress().getPort() + "/transfers");
  }
  @LocalServerPort int port;
  @Autowired DtnService service;
  @Autowired InputBufferService inputs;
  @Autowired AgentRepository agents;
  @Autowired ObjectMapper json;
  @Autowired server.central.agent.AgentMessageService agentMessages;
  @MockitoBean AgentCommandService commands;
  @MockitoBean AgentConnectionRegistry connections;
  @AfterAll static void shutdown() { external.stop(0); }

  @Test void centralModeServesAgentPvtAfterCaptureCompletionAndReload() throws Exception {
    var observation = new server.shared.codec.GrawCodec.ObservationEpoch(123.5, 2400, 18, 1, 1, List.of());
    byte[] record = server.shared.codec.GrawCodec.encode(new server.shared.codec.GrawCodec.Envelope(
        UUID.randomUUID(), UUID.randomUUID(), 0, Instant.now(), observation));
    byte[] raw = java.nio.ByteBuffer.allocate(record.length + 4).putInt(record.length).put(record).array();
    var input = inputs.create("capture.graw", 0, InputKind.GNSS_CAPTURE);
    inputs.append(input.inputId(), 0, raw);
    var pvt = new DtnModels.Pvt();
    pvt.setWeek(2400); pvt.setTowSeconds(123.5);
    pvt.setPositionValid(true); pvt.setVelocityValid(true);
    pvt.setEcefMeters(new double[] {1, 2, 3});
    pvt.setVelocityMetersPerSecond(new double[] {0.1, 0.2, 0.3});
    agentMessages.handle(Envelope.of(MessageType.STATUS, "capture-sender", AgentRole.SENDER,
        input.inputId(), json.valueToTree(new Progress(EventType.GNSS_STATUS, 100,
            "SingleEpochComplete", "Complete", Map.of("pvt", List.of(pvt))))));
    assertTrue(inputs.get(input.inputId()).complete());
    assertNotNull(inputs.get(input.inputId()).capturedPvtJson());
    assertFalse(json.valueToTree(inputs.get(input.inputId())).has("capturedPvtJson"));
    // Repeating the ordinary completion API must not erase the saved preview.
    inputs.complete(input.inputId());
    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
        "http://localhost:" + port + "/lnis/api/v1/dtn/inputs/" + input.inputId() + "/pvt"))
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(json.valueToTree(List.of(pvt)), json.readTree(response.body()));
    inputs.remove(input.inputId());
  }

  @Test void roundTripAndAuthenticationAndPayloadIntegrity() throws Exception {
    callback.set(URI.create("http://127.0.0.1:" + port + "/lnis/api/v1/dtn/receive"));
    when(connections.online(anyString())).thenReturn(true);
    when(connections.online("dtn-receiver")).thenReturn(false);
    agents.save(new AgentEntity("dtn-sender", AgentRole.SENDER, AgentState.READY, Instant.now(),
        "test", 1, "Windows", "amd64", List.of(), null));
    agents.save(new AgentEntity("dtn-receiver", AgentRole.RECEIVER, AgentState.READY, Instant.now(),
        "test", 1, "Windows", "amd64", List.of(), null));
    Map<String, DtnChunks> incoming = new ConcurrentHashMap<>();
    Path library = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
    try (var codec = NativeAfsCodec.load(library)) {
      DtnProcessor processor = new DtnProcessor(codec, library);
      doAnswer(invocation -> {
        String agent = invocation.getArgument(0); UUID id = invocation.getArgument(1);
        var args = json.valueToTree(invocation.getArgument(3));
        String mode = args.path("mode").asText();
        byte[] data = incoming.computeIfAbsent(mode, ignored -> new DtnChunks()).append(
            args.path("index").asInt(), args.path("last").asBoolean(),
            Base64.getDecoder().decode(args.path("dataBase64").asText()));
        if (data != null) {
          var result = "PREPARE".equals(mode) ? processor.prepare(id, data)
              : processor.receive(id, json.readValue(data, DtnModels.Transfer.class));
          var payload = json.createObjectNode().put("index", 0).put("last", true)
              .put("dataBase64", Base64.getEncoder().encodeToString(json.writeValueAsBytes(result)));
          service.agentData(Envelope.of(MessageType.DTN_DATA, agent,
              "PREPARE".equals(mode) ? AgentRole.SENDER : AgentRole.RECEIVER, id, payload));
        }
        return UUID.randomUUID();
      }).when(commands).command(anyString(), any(UUID.class), eq(CommandType.DTN_PROCESS), any());
      byte[] source = NativePvtIntegrationTest.validSample();
      var input = inputs.create("dtn.graw", source.length, InputKind.GRAW_UPLOAD);
      inputs.append(input.inputId(), 0, source); inputs.complete(input.inputId());
      HttpClient client = HttpClient.newHttpClient();
      var preview = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
          + "/lnis/api/v1/dtn/inputs/" + input.inputId() + "/observations")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, preview.statusCode());
      var previewJson = json.readTree(preview.body());
      assertEquals(2400, previewJson.path("epochs").get(0).path("observation").path("week").asInt());
      assertTrue(previewJson.path("navigationCount").asInt() > 0);
      assertFalse(previewJson.path("epochs").get(0).path("observation").path("observations").isEmpty());
      String body = json.writeValueAsString(Map.of("inputId", input.inputId(),
          "senderAgentId", "dtn-sender", "receiverAgentId", "dtn-receiver",
          "sendUrl", "http://127.0.0.1:" + external.getAddress().getPort() + "/transfers/selected"));
      var startRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/lnis/api/v1/dtn/tests"))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var start = client.send(startRequest, HttpResponse.BodyHandlers.ofString());
      assertEquals(202, start.statusCode(), start.body());
      UUID id = UUID.fromString(json.readTree(start.body()).path("testId").asText());
      long deadline = System.nanoTime()+20_000_000_000L;
      while (service.get(id).getReceivedJson() == null && System.nanoTime() < deadline) Thread.sleep(50);
      assertNotNull(service.get(id).getReceivedJson());
      assertEquals("/transfers/selected", destinationPath.get());
      assertNull(forwardedAuthorization.get());
      assertTrue(service.get(id).getSendUrl().endsWith("/transfers/selected"));
      // H2에서 다시 읽은 원문과 실제 외부 HTTP 본문이 바이트 단위로 일치해야 한다.
      assertArrayEquals(packet.get(), service.payload(id, "received").getBody());
      assertArrayEquals(packet.get(), service.payload(id, "sent").getBody());
      HttpResponse<byte[]> payloadDownload = client.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + port + "/lnis/api/v1/dtn/tests/" + id + "/payload/received?download=true"))
          .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, payloadDownload.statusCode());
      assertArrayEquals(packet.get(), payloadDownload.body());
      service.tick();
      assertEquals("WAITING_RECEIVER", service.get(id).getState());
      when(connections.online("dtn-receiver")).thenReturn(true);
      service.tick();
      assertEquals("COMPLETED", service.get(id).getState(), service.get(id).getMessage());
      assertEquals("PASS", json.readTree(service.get(id).getComparisonJson()).path("verdict").asText());
      assertNotNull(service.get(id).getComparisonJson());
      assertFalse(json.readTree(service.get(id).getSentJson()).has("pvt"));
      assertFalse(json.readTree(service.get(id).getSentJson()).has("observations"));
      assertEquals(previewJson, json.readTree(service.get(id).getObservationsJson()));
      assertEquals(401, callback(client, null, packet.get()).statusCode());
      assertEquals(202, callback(client, "test-receive-token", packet.get()).statusCode());
      var modified = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(packet.get());
      modified.put("prn", 2);
      assertEquals(400, callback(client, "test-receive-token", json.writeValueAsBytes(modified)).statusCode());
      verify(commands, times(2)).command(anyString(), eq(id), eq(CommandType.DTN_PROCESS), any());
    }
  }
  private HttpResponse<String> callback(HttpClient client, String token, byte[] body) throws Exception {
    var request = HttpRequest.newBuilder(callback.get()).header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
