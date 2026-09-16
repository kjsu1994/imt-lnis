package server.central.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import server.LnisApplication;
import server.agent.codec.NativePvtIntegrationTest;
import server.central.agent.AgentRepository;
import server.central.dtn.DtnJob;
import server.central.dtn.DtnService;
import server.central.input.InputBufferService;
import server.central.session.ActiveSessionLockRepository;
import server.central.session.CreateSessionRequest;
import server.central.session.SessionService;
import server.shared.model.LnisModels.*;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 별도 Spring 컨텍스트와 파일 H2, 실제 HTTP 및 네이티브 실행기로 독립 노드를 검증한다. */
class IndependentNodeIntegrationTest {
    @TempDir
    Path directory;

    @Test
    @Timeout(240)
    void dtnRoundTripAndAfsTestsUseIndependentDatabases() throws Exception
    {
        int receiverPort = tcpPort();
        int senderPort = tcpPort();
        AtomicReference<byte[]> delivered = new AtomicReference<>();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpServer adapter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        adapter.createContext("/transfer", exchange -> {
            try (exchange) {
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                ObjectMapper adapterMapper = new ObjectMapper();
                var callbackBody = (com.fasterxml.jackson.databind.node.ObjectNode) adapterMapper.readTree(bytes);
                callbackBody.put("dtnLogsBase64", java.util.Base64.getEncoder().encodeToString(
                        "[17:12:36.538] [REST API] accepted\n[17:12:37.001] [HDTN] forwarded".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                bytes = adapterMapper.writeValueAsBytes(callbackBody);
                delivered.set(bytes);
                HttpRequest callback = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + receiverPort
                                + "/lnis/api/v1/dtn/receive"))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer test-dtn-receive")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                try {
                    HttpResponse<String> response = http.send(callback, HttpResponse.BodyHandlers.ofString());
                    exchange.sendResponseHeaders(response.statusCode() == 202 ? 202 : 502, -1);
                } catch (Exception error) {
                    exchange.sendResponseHeaders(500, -1);
                }
            }
        });
        adapter.start();
        try (ConfigurableApplicationContext receiver = node("receiver", receiverPort, senderPort);
                ConfigurableApplicationContext sender = node("sender", senderPort, receiverPort)) {
            await(() -> ready(sender, "sender-1") && ready(sender, "receiver-1"), 20);
            ObjectMapper mapper = sender.getBean(ObjectMapper.class);
            String settingsUrl = "http://127.0.0.1:" + senderPort + "/lnis/api/v1/node/connection";
            byte[] addressBody = mapper.writeValueAsBytes(Map.of("ip", "127.0.0.1", "port", receiverPort));
            HttpResponse<String> probe = http.send(HttpRequest.newBuilder(URI.create(settingsUrl + "/test"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(addressBody))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, probe.statusCode(), probe.body());
            assertTrue(mapper.readTree(probe.body()).path("ready").asBoolean());
            HttpResponse<String> saved = http.send(HttpRequest.newBuilder(URI.create(settingsUrl))
                    .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofByteArray(addressBody))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
            assertFalse(saved.body().contains("test-node-management"));
            assertEquals(receiverPort, mapper.readTree(saved.body()).path("port").asInt());
            await(() -> ready(sender, "sender-1") && ready(sender, "receiver-1"), 15);
            InputBufferService inputs = sender.getBean(InputBufferService.class);
            byte[] source = NativePvtIntegrationTest.validSample();
            UUID input = inputs.create("node-test.graw", source.length, InputKind.GRAW_UPLOAD).inputId();
            inputs.append(input, 0, source);
            inputs.complete(input);
            var preview = sender.getBean(server.central.dtn.DtnInputViewController.class).pvt(input);
            assertTrue(preview.getFirst().isPositionValid());
            assertTrue(preview.getFirst().isVelocityValid());
            DtnService tx = sender.getBean(DtnService.class);
            DtnService rx = receiver.getBean(DtnService.class);
            var hdtn = mapper.readValue("""
                    {"maxNumberOfBundlesInPipeline":50,"maxSumOfBundleBytesInPipeline":50000000,
                    "enforceBundlePriority":true,"neighborDepletedStorageDelaySeconds":10,
                    "maxBundleSizeBytes":10485760,"storageDeletionPolicy":"DELETE_AFTER_FORWARDING"}
                    """, server.shared.model.DtnModels.HdtnConfig.class);
            UUID test = tx.create(input, "sender-1", "receiver-1",
                    "http://127.0.0.1:" + adapter.getAddress().getPort() + "/transfer",
                    "AFS_METADATA", "DTN", "HDTN", hdtn).getId();
            await(() -> "COMPLETED".equals(tx.get(test).getState()) || "FAILED".equals(tx.get(test).getState()), 40);
            assertEquals("COMPLETED", tx.get(test).getState(), tx.get(test).getMessage());
            assertEquals("PASS", mapper.readTree(tx.get(test).getComparisonJson()).path("verdict").asText());
            assertEquals(mapper.readTree(mapper.writeValueAsBytes(hdtn)), mapper.readTree(delivered.get()).get("hdtnConfig"));
            DtnJob received = rx.get(test);
            assertEquals(tx.get(test).getHdtnConfigJson(), received.getHdtnConfigJson());
            assertEquals("COMPLETED", received.getState(), received.getMessage());
            assertNull(received.getSentJson());
            assertNull(received.getReferenceJson());
            assertNull(received.getInputId());
            assertNull(received.getComparisonJson());
            assertNull(tx.get(test).getReceivedJson());
            assertNotNull(tx.get(test).getReceivedAt());
            assertArrayEquals(delivered.get(), rx.payload(test, "received").getBody());
            var transmitted = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(delivered.get());
            transmitted.remove("dtnLogsBase64");
            assertEquals(transmitted, mapper.readTree(tx.payload(test, "sent").getBody()));
            var senderLogs = sender.getBean(server.central.dtn.DtnLogService.class);
            var receiverLogs = receiver.getBean(server.central.dtn.DtnLogService.class);
            assertEquals(2, receiverLogs.adapterEntries(test).size());
            assertEquals(receiverLogs.adapterEntries(test), senderLogs.adapterEntries(test));
            assertThrows(IllegalArgumentException.class, () -> receiver.getBean(InputBufferService.class).get(input));
            // 최초 원문을 지키고 중복 전달로 계산을 다시 시작하지 않는다.
            rx.receive("Bearer test-dtn-receive", delivered.get());
            assertEquals("COMPLETED", rx.get(test).getState());
            assertEquals(2, receiverLogs.adapterEntries(test).size());
            com.fasterxml.jackson.databind.node.ObjectNode changed = mapper.readTree(delivered.get()).deepCopy();
            changed.put("prn", 2);
            byte[] modified = mapper.writeValueAsBytes(changed);
            assertThrows(IllegalArgumentException.class, () -> rx.receive("Bearer test-dtn-receive", modified));
            HttpResponse<String> unauthorized = http.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + receiverPort + "/lnis/api/v1/node/peer/dtn/tests/" + test)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());
            assertEquals(302, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + receiverPort + "/lnis/dtntest/sender")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());

            SessionService txSessions = sender.getBean(SessionService.class);
            for (TestType type : TestType.values()) {
                await(() -> ready(sender, "sender-1") && ready(sender, "receiver-1"), 15);
                CreateSessionRequest request = new CreateSessionRequest("sender-1", "receiver-1", input,
                        new AfsSettings(1), new TestOptions(type, 1, 1, 10, Map.of()));
                UUID session = txSessions.create(request).sessionId();
                await(() -> txSessions.snapshot(session).rxResult() != null, 25);
                await(() -> sender.getBean(ActiveSessionLockRepository.class).current().isEmpty(), 10);
                SessionSnapshot result = txSessions.snapshot(session);
                assertNotNull(result.txResult(), type.name());
                assertNotNull(result.rxResult(), type.name());
                assertNotEquals(SessionState.CANCELLED, result.state(), type.name());
                assertTrue(receiver.getBean(ActiveSessionLockRepository.class).current().isEmpty());
                if (type == TestType.TEST_A_NORMAL) {
                    assertEquals(Verdict.PASS, result.verdict());
                } else {
                    assertTrue(result.txResult().counters().injectedBitCount() > 0);
                }
            }
            // 파일 H2를 닫고 같은 수신 노드를 다시 열어 최초 원문과 결과가 보존되는지 확인한다.
            receiver.close();
            try (ConfigurableApplicationContext restarted = node("receiver", receiverPort, senderPort)) {
                DtnService restored = restarted.getBean(DtnService.class);
                assertEquals("COMPLETED", restored.get(test).getState());
                assertEquals(mapper.readTree(mapper.writeValueAsBytes(hdtn)), mapper.readTree(restored.get(test).getHdtnConfigJson()));
                assertNull(restored.get(test).getReferenceJson());
                assertArrayEquals(delivered.get(), restored.payload(test, "received").getBody());
                assertTrue(restarted.getBean(ActiveSessionLockRepository.class).current().isEmpty());
            }
            sender.close();
            try (ConfigurableApplicationContext restarted = node("sender", senderPort, tcpPort())) {
                // 환경 기본 주소를 바꿔 재시작해도 화면에서 H2에 저장한 주소가 우선한다.
                assertEquals(receiverPort, restarted.getBean(NodeConnectionService.class).configuration().getPort());
            }
        } finally {
            adapter.stop(0);
        }
    }

    @Test
    @Timeout(120)
    void cancellingAnUnresponsiveTransferStopsBothNodesAndRejectsLateCallbacks() throws Exception
    {
        int receiverPort = tcpPort(), senderPort = tcpPort();
        var body = new java.util.concurrent.LinkedBlockingQueue<byte[]>();
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer adapter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        adapter.createContext("/transfers", exchange -> {
            try (exchange) {
                body.add(exchange.getRequestBody().readAllBytes());
                try { release.await(30, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(202, -1);
            }
        });
        adapter.start();
        HttpClient http = HttpClient.newHttpClient();
        try (ConfigurableApplicationContext receiver = node("receiver", receiverPort, senderPort);
                ConfigurableApplicationContext sender = node("sender", senderPort, receiverPort)) {
            await(() -> ready(sender, "sender-1") && ready(sender, "receiver-1"), 20);
            DtnService tx = sender.getBean(DtnService.class), rx = receiver.getBean(DtnService.class);
            InputBufferService inputs = sender.getBean(InputBufferService.class);
            byte[] source = NativePvtIntegrationTest.validSample();
            UUID input = inputs.create("cancel.graw", source.length, InputKind.GRAW_UPLOAD).inputId();
            inputs.append(input, 0, source); inputs.complete(input);
            String url = "http://127.0.0.1:" + adapter.getAddress().getPort();
            UUID id = tx.create(input, "sender-1", "receiver-1", url).getId();
            byte[] packet = body.poll(15, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(packet);
            var cancel = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + senderPort
                    + "/lnis/api/v1/dtn/tests/" + id + "/cancel")).timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(200, http.send(cancel, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("CANCELLED", tx.get(id).getState());
            await(() -> "CANCELLED".equals(rx.get(id).getState()) && !Boolean.TRUE.equals(tx.get(id).getCancelPending()), 10);
            assertEquals(200, http.send(cancel, HttpResponse.BodyHandlers.ofString()).statusCode());
            var callback = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + receiverPort + "/lnis/api/v1/dtn/receive"))
                    .header("Authorization", "Bearer test-dtn-receive").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(packet)).build();
            assertEquals(409, http.send(callback, HttpResponse.BodyHandlers.ofString()).statusCode());
            release.countDown();
            await(() -> ready(sender, "sender-1") && ready(sender, "receiver-1"), 10);
            UUID next = tx.create(input, "sender-1", "receiver-1", url).getId();
            assertNotEquals(id, next); assertNotNull(body.poll(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLED", tx.get(id).getState());
            tx.cancel(next);
            await(() -> !Boolean.TRUE.equals(tx.get(next).getCancelPending()), 10);

            UUID early = UUID.randomUUID();
            String peerCancel = "http://127.0.0.1:" + receiverPort + "/lnis/api/v1/node/peer/dtn/tests/" + early + "/cancel";
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(peerCancel))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(peerCancel))
                    .header("Authorization", "Bearer test-node-management")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            NodeDtnRegistration registration = new NodeDtnRegistration();
            registration.setTestId(early); registration.setSenderAgentId("sender-1"); registration.setReceiverAgentId("receiver-1");
            registration.setProfile(server.shared.model.DtnModels.PROFILE); registration.setPayloadSha256("a".repeat(64));
            assertEquals("CANCELLED", receiver.getBean(NodeDtnService.class).accept(registration).getState());
        } finally { release.countDown(); adapter.stop(0); }
    }

    private ConfigurableApplicationContext node(String role, int port, int peerPort)
    {
        return new SpringApplicationBuilder(LnisApplication.class).profiles("server", "node").run(
                "--server.port=" + port,
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve(role).toAbsolutePath().toString().replace('\\', '/'),
                "--spring.jpa.hibernate.ddl-auto=update",
                "--lnis.storage.data-directory=" + directory.resolve(role + "-files"),
                "--lnis.node.role=" + role,
                "--lnis.node.base-url=http://127.0.0.1:" + port,
                "--lnis.node.peer-url=http://127.0.0.1:" + peerPort,
                "--lnis.node.management-token=test-node-management",
                "--lnis.dtn.receive-token=test-dtn-receive",
                "--lnis.native.dir=" + Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64")).toAbsolutePath(),
                "--logging.level.root=WARN");
    }

    private static boolean ready(ConfigurableApplicationContext context, String id)
    {
        return context.getBean(AgentRepository.class).find(id)
                .map(agent -> agent.state() == AgentState.READY).orElse(false);
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "제한 시간 내 상태 전이가 완료되지 않았습니다.");
    }

    private static int tcpPort() throws Exception
    {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

}
