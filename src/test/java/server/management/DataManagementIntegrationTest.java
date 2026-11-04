package server.management;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static server.management.DataManagementService.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import server.common.LnisModels.*;
import server.config.StorageCleanupService;
import server.dtn.*;
import server.gnss.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:data-management;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "lnis.storage.cleanup-delay=PT24H"
        })
@ActiveProfiles("server")
@AutoConfigureMockMvc
class DataManagementIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    server.node.LocalNodeLifecycle localRuntime;

    static final Path directory = temp();
    private static final ThreadLocal<List<String>> projectionSql = new ThreadLocal<>();

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class QueryInspection {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer inspectQueries() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",
                    (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {
                        List<String> captured = projectionSql.get();
                        if (captured != null) captured.add(sql);
                        return sql;
                    });
        }
    }

    static Path temp() {
        try {
            return Files.createTempDirectory("lnis-management-test-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void removeTestFiles() throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        assertTrue(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()));
        assertTrue(root.getFileName().toString().startsWith("lnis-management-test-"));
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry p) {
        p.add("lnis.storage.data-directory", () -> directory.toString());
        p.add("lnis.iq.directory", () -> directory.resolve("iq").toString());
    }

    @MockitoSpyBean DataManagementService management;
    @Autowired DataResetService reset;
    @Autowired DataManagementGuard guard;
    @Autowired DtnRepository jobs;
    @Autowired DtnService dtn;
    @Autowired DtnReceiptService receipts;
    @Autowired DtnLogService logs;
    @Autowired InputBufferService inputs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired StorageCleanupService oldCleanup;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    @Autowired MockMvc mvc;
    @MockitoSpyBean GrawFileStorage files;
    final Instant old = Instant.now().minus(Duration.ofDays(100));

    void tx(Runnable action) {
        new TransactionTemplate(tm).executeWithoutResult(s -> action.run());
    }

    DtnJob job(UUID input) {
        DtnJob j = new DtnJob();
        j.setId(UUID.randomUUID());
        j.setInputId(input);
        j.setState("COMPLETED");
        j.setTestType("AFS_METADATA");
        j.setCreatedAt(old);
        j.setUpdatedAt(old);
        return jobs.saveAndFlush(j);
    }

    Key key(DtnJob job) {
        return new Key(Kind.DTN, job.getId());
    }

    Operation remove(Key key) {
        return management.execute(management.preview(List.of(key)).token(), "TEST");
    }

    @BeforeEach
    void clear() {
        guard.resetting(false);
        guard.revision(null);
        tx(
                () -> {
                    for (String entity :
                            List.of("DtnReceipt", "DtnLogEntry", "DtnJob", "ManagementEntry")) {
                        em.createQuery("delete from " + entity).executeUpdate();
                    }
                });
    }

    @Test
    void pendingCaptureIsProtectedFromRetentionCleanup() throws Exception {
        var records = GrawCodec.splitLengthPrefixed(server.afs.NativePvtIntegrationTest.validSample());
        byte[] epoch = records.stream().filter(record -> GrawCodec.decode(record).message()
                instanceof GrawCodec.ObservationEpoch).findFirst().orElseThrow();
        byte[] data = java.nio.ByteBuffer.allocate(epoch.length + 4).putInt(epoch.length).put(epoch).array();
        var input = inputs.create("pending.graw", data.length, InputKind.GNSS_CAPTURE);
        try {
            inputs.append(input.inputId(), 0, data);
            inputs.awaitCaptureDecision(input.inputId(), "[]");
            jdbc.update("update input_buffers set created_at=?, completed_at=? where input_id=?",
                    java.sql.Timestamp.from(old), java.sql.Timestamp.from(old), input.inputId());
            var row = management.list(Kind.INPUT, 0, input.inputId().toString(), "", null, null).items().getFirst();
            assertFalse(row.blocked().isBlank());
            inputs.removeExpired(inputs.get(input.inputId()));
            assertEquals("AWAITING_DECISION", inputs.get(input.inputId()).captureDecision());
            assertEquals("FAILED", remove(new Key(Kind.INPUT, input.inputId())).results().getFirst().status());
            assertEquals("AWAITING_DECISION", inputs.get(input.inputId()).captureDecision());
        } finally {
            inputs.discardCapture(input.inputId());
        }
    }

    @Test
    void defaultsKeepDtnAndArchiveTablesWithoutExposingBodies() throws Exception {
        var j = job(null);
        j.setSentJson("PRIVATE_PAYLOAD_MARKER");
        jobs.saveAndFlush(j);
        UUID afs = UUID.randomUUID();
        jdbc.execute(
                "create table if not exists test_session (session_id uuid primary key, request_json clob)");
        jdbc.update(
                "insert into test_session(session_id,request_json) values (?,?)",
                afs,
                "archived-afs");
        assertFalse(management.settings().tests().enabled());
        oldCleanup.cleanup();
        management.cleanup();
        assertTrue(jobs.existsById(j.getId()));
        mvc.perform(get("/lnis/api/v1/data-management/items").param("kind", "DTN"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "PRIVATE_PAYLOAD_MARKER"))));
        mvc.perform(get("/lnis/api/v1/data-management/items").param("kind", "AFS"))
                .andExpect(status().isGone());
        assertThrows(
                RuntimeException.class, () -> management.preview(List.of(new Key(Kind.AFS, afs))));
        assertFalse(((Map<?, ?>) management.summary().get("counts")).containsKey("AFS"));
        assertEquals(
                "archived-afs",
                jdbc.queryForObject(
                        "select request_json from test_session where session_id=?",
                        String.class,
                        afs));
    }

    @Test
    void oldAfsManagementHistoryIsReadableButCannotBeReplayed() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var key = new Key(Kind.AFS, UUID.randomUUID());
        var row = new Row(key, "legacy", "COMPLETED", old, old, 0, false, "");
        var preview =
                new Preview(
                        UUID.randomUUID(),
                        "sender",
                        Instant.now().plusSeconds(300),
                        List.of(new PlanItem(row, List.of(), 0, 0, 0, "")));
        var operation =
                new Operation(
                        UUID.randomUUID(),
                        old,
                        "MANUAL",
                        List.of(new Result(key, "FAILED", "old failure")));
        String operationJson = json.writeValueAsString(operation),
                previewJson = json.writeValueAsString(preview);
        tx(
                () -> {
                    em.persist(new ManagementEntry("OP:" + operation.id(), "OP", operationJson));
                    em.persist(
                            new ManagementEntry(
                                    "PREVIEW:" + preview.token(), "PREVIEW", previewJson));
                });
        assertEquals(key, management.history().getFirst().results().getFirst().key());
        assertThrows(RuntimeException.class, () -> management.retry(operation.id()));
        assertThrows(RuntimeException.class, () -> management.execute(preview.token(), "TEST"));
        assertFalse(guard.deleted("AFS", key.id()));
    }

    @Test
    void sharedInputPinsLateCallbacksAndPendingTrialsAreProtected() throws Exception {
        var input = inputs.create("shared.graw", 3, InputKind.GRAW_UPLOAD);
        inputs.append(input.inputId(), 0, new byte[] {1, 2, 3});
        var first = job(input.inputId());
        var second = job(input.inputId());
        assertTrue(management.preview(List.of(key(first))).items().getFirst().related().isEmpty());
        management.pin(key(first), true);
        assertThrows(RuntimeException.class, () -> inputs.remove(input.inputId()));
        assertEquals("FAILED", remove(key(first)).results().getFirst().status());
        management.pin(key(first), false);
        assertEquals("DELETED", remove(key(first)).results().getFirst().status());
        assertNotNull(inputs.get(input.inputId()));
        second.setCancelPending(true);
        jobs.saveAndFlush(second);
        assertEquals("FAILED", remove(key(second)).results().getFirst().status());
        second.setCancelPending(false);
        jobs.saveAndFlush(second);
        var body =
                ("{\"testId\":\"" + first.getId() + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(
                RuntimeException.class, () -> receipts.capture(body, "application/json", false));
        assertTrue(
                management
                        .preview(List.of(key(second)))
                        .items()
                        .getFirst()
                        .related()
                        .contains(new Key(Kind.INPUT, input.inputId())));
        assertEquals("DELETED", remove(key(second)).results().getFirst().status());
        assertThrows(RuntimeException.class, () -> inputs.get(input.inputId()));
    }

    @Test
    void backgroundQueriesSelectOnlySchedulingColumns() {
        DtnJob stored = job(null);
        stored.setSentJson("large-sent-body");
        stored.setReceivedJson("large-received-body");
        stored.setReferenceStatus("WAITING");
        jobs.saveAndFlush(stored);
        var captured = new ArrayList<String>();
        projectionSql.set(captured);
        try {
            assertEquals(stored.getId(), jobs.findTasksByStateIn(List.of("COMPLETED")).getFirst().getId());
            assertEquals(stored.getId(), jobs.findTasksByReferenceStatus("WAITING").getFirst().getId());
        } finally {
            projectionSql.remove();
        }
        var selects = captured.stream().map(String::toLowerCase)
                .filter(sql -> sql.contains(" from dtn_job ")).toList();
        assertEquals(2, selects.size(), captured.toString());
        for (String sql : selects) {
            for (String column : List.of("sent_json", "received_json", "reference_source_base64",
                    "observations_json", "receiver_json", "delay_evidence_json")) {
                assertFalse(sql.contains(column), sql);
            }
        }
    }

    @Test
    void unrelatedCompletedHistoryCanBeDeletedDuringDeliveryWait() {
        DtnJob waiting = job(null);
        waiting.setState("WAITING_DTN");
        jobs.saveAndFlush(waiting);
        DtnJob reference = job(null);
        reference.setReferenceStatus("WAITING");
        jobs.saveAndFlush(reference);
        DtnJob pinned = job(null);
        management.pin(key(pinned), true);
        DtnJob completed = job(null);
        assertEquals("DELETED", remove(key(completed)).results().getFirst().status());
        assertEquals("FAILED", remove(key(waiting)).results().getFirst().status());
        assertEquals("FAILED", remove(key(reference)).results().getFirst().status());
        assertEquals("FAILED", remove(key(pinned)).results().getFirst().status());
        assertTrue(jobs.existsById(waiting.getId()));
        assertTrue(jobs.existsById(reference.getId()));
    }

    @Test
    void selectedTrialReceiptsRemainAvailableBeyondGlobalRecentPage() throws Exception {
        UUID target = UUID.randomUUID();
        byte[] original = ("{\"testId\":\"" + target + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        receipts.capture(original, "application/json", false, old);
        for (int i = 0; i < 51; i++) {
            receipts.capture(("{\"testId\":\"" + UUID.randomUUID() + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json", false);
        }
        assertTrue(receipts.recent().stream().noneMatch(row -> target.equals(row.getTestId())));
        assertEquals(1, receipts.recent(target).size());
        mvc.perform(get("/lnis/api/v1/dtn/receipts").param("testId", target.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].testId").value(target.toString()))
                .andExpect(jsonPath("$[0].body").doesNotExist());
    }

    @Test
    void failedFileDeletionCanBeRetriedWithoutDeletingTrialEarly() {
        var input = inputs.create("retry.graw", 3, InputKind.GRAW_UPLOAD);
        inputs.append(input.inputId(), 0, new byte[] {1, 2, 3});
        var j = job(input.inputId());
        logs.add(j.getId(), "TEST", "TEST", false, "retained until delete succeeds");
        doThrow(new IllegalStateException("simulated filesystem failure"))
                .when(files)
                .delete(input.inputId());
        var failed = remove(key(j));
        assertEquals("FAILED", failed.results().getFirst().status());
        assertTrue(jobs.existsById(j.getId()));
        assertNotNull(inputs.get(input.inputId()));
        doCallRealMethod().when(files).delete(input.inputId());
        var retried = management.execute(management.retry(failed.id()).token(), "TEST_RETRY");
        assertEquals("DELETED", retried.results().getFirst().status());
        assertFalse(jobs.existsById(j.getId()));
        assertTrue(logs.read(j.getId(), 0).isEmpty());
        assertTrue(guard.deleted("DTN", j.getId()));
    }

    @Test
    void policyNeedsPreviewPersistsAndOnlyDeletesExpiredUnpinnedData() throws Exception {
        var expired = job(null);
        var fixed = job(null);
        management.pin(key(fixed), true);
        var recent = job(null);
        recent.setUpdatedAt(Instant.now());
        jobs.saveAndFlush(recent);
        var proposed =
                new Settings(new Policy(true, 30), new Policy(false, 30), new Policy(false, 30));
        var preview = management.previewPolicy(proposed);
        assertTrue(
                preview.preview().items().stream()
                        .anyMatch(i -> i.row().key().equals(key(expired))));
        assertFalse(
                preview.preview().items().stream()
                        .anyMatch(
                                i ->
                                        i.row().key().equals(key(fixed))
                                                || i.row().key().equals(key(recent))));
        management.savePolicy(preview.preview().token());
        em.clear();
        assertEquals(proposed, management.settings());
        management.cleanup();
        assertFalse(jobs.existsById(expired.getId()));
        assertTrue(jobs.existsById(fixed.getId()));
        assertTrue(jobs.existsById(recent.getId()));
        assertThrows(IllegalArgumentException.class, () -> new Policy(true, 0));
        assertThrows(RuntimeException.class, () -> management.execute(UUID.randomUUID(), "TEST"));
        Files.createDirectories(directory.resolve("iq"));
        UUID id = UUID.randomUUID();
        Path bin = directory.resolve("iq").resolve(id + ".bin");
        Files.write(bin, new byte[] {1, 2, 3});
        assertTrue(
                management.list(Kind.IQ, 0, id.toString(), "", null, null).items().stream()
                        .anyMatch(r -> r.bytes() == 3));
        management.pin(new Key(Kind.IQ, id), true);
        assertThrows(RuntimeException.class, () -> dtn.deleteIq(id));
        management.pin(new Key(Kind.IQ, id), false);
        assertEquals("DELETED", remove(new Key(Kind.IQ, id)).results().getFirst().status());
        assertFalse(Files.exists(bin));
    }

    @Test
    void receiverAllowsOnlyDedicatedManagementMutations() throws Exception {
        var properties =
                new server.node.NodeProperties(
                        new org.springframework.mock.env.MockEnvironment()
                                .withProperty("lnis.node.role", "receiver"));
        var filter = new server.node.NodeRoleFilter(properties);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var reached = new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(
                new org.springframework.mock.web.MockHttpServletRequest(
                        "POST", "/lnis/api/v1/data-management/preview"),
                response,
                (a, b) -> reached.set(true));
        assertTrue(reached.get());
        reached.set(false);
        response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(
                new org.springframework.mock.web.MockHttpServletRequest(
                        "POST", "/lnis/api/v1/dtn/tests"),
                response,
                (a, b) -> reached.set(true));
        assertFalse(reached.get());
        assertEquals(409, response.getStatus());
    }

    @Test
    void deletionWaitsForOngoingRequestAndDeletesLinkedReceiptTogether() throws Exception {
        var j = job(null);
        receipts.capture(
                ("{\"testId\":\"" + j.getId() + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json",
                false);
        var preview = management.preview(List.of(key(j)));
        assertEquals(1, preview.items().getFirst().receipts());
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var reader =
                    executor.submit(
                            () -> {
                                var lock = guard.gate.readLock();
                                lock.lock();
                                try {
                                    held.countDown();
                                    release.await();
                                } finally {
                                    lock.unlock();
                                }
                                return null;
                            });
            assertTrue(held.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var deletion = executor.submit(() -> management.execute(preview.token(), "TEST"));
            try {
                assertThrows(
                        java.util.concurrent.TimeoutException.class,
                        () -> deletion.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertEquals(
                    "DELETED",
                    deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)
                            .results()
                            .getFirst()
                            .status());
            reader.get();
        } finally {
            release.countDown();
        }
        assertEquals(0, management.list(Kind.RECEIPT, 0, "", "", null, null).total());
    }
    DataResetService.View awaitReset() throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var status = reset.status();
            if (status != null && !"RUNNING".equals(status.state())) return status;
            Thread.sleep(20);
        }
        fail("초기화 처리가 제한 시간 안에 끝나지 않았습니다.");
        return null;
    }

    @Test
    void querySelectionIncludesEveryPageAndPreservesExcludedPins() {
        tx(() -> {
            for (int index = 0; index < 503; index++) {
                DtnJob job = new DtnJob();
                job.setId(UUID.randomUUID());
                job.setTestType("GNSS_RAW");
                job.setState("COMPLETED");
                job.setCreatedAt(old);
                job.setUpdatedAt(old);
                em.persist(job);
            }
        });
        var pinned = management.list(Kind.DTN, 0, "", "", null, null).items().getFirst().key();
        management.pin(pinned, true);
        var preview = management.previewQuery(new QuerySelection(Kind.DTN, "", "COMPLETED", null, null));
        assertEquals(502, preview.items().size());
        assertEquals(1, preview.excluded().size());
        assertEquals(502L, preview.counts().get("DTN"));
        var result = management.execute(preview.token(), "TEST_QUERY");
        assertEquals(502, result.results().size());
        assertTrue(result.results().stream().allMatch(row -> row.status().equals("DELETED")));
        assertTrue(jobs.existsById(pinned.id()));
        assertEquals(1, management.list(Kind.DTN, 0, "", "", null, null).total());
    }

    @Test
    void trialResetRequiresExactConfirmationAndPreservesInputsFilesAndPolicies() throws Exception {
        var input = inputs.create("preserved.graw", 3, InputKind.GRAW_UPLOAD);
        inputs.append(input.inputId(), 0, new byte[] {1, 2, 3});
        Path inputPath = directory.resolve("files/inputs/" + input.inputId() + ".part");
        DtnJob job = job(input.inputId());
        management.pin(key(job), true);
        receipts.capture(("{\"testId\":\"" + job.getId() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json", false);
        logs.add(job.getId(), "TEST", "검증", false, "삭제 대상");
        var policy = new Settings(new Policy(true,30), new Policy(false,30), new Policy(false,30));
        management.savePolicy(management.previewPolicy(policy).preview().token());
        jdbc.execute("create table if not exists test_session (session_id uuid primary key, request_json clob)");
        jdbc.update("insert into test_session(session_id,request_json) values (?,?)", UUID.randomUUID(), "legacy");
        var preview = reset.preview(DataResetService.Mode.TRIALS);
        assertTrue(preview.counts().get("ARCHIVE") > 0);
        assertTrue(preview.counts().get("PIN") > 0);
        assertTrue(jobs.existsById(job.getId()));
        assertThrows(IllegalArgumentException.class, () -> reset.start(preview.token(), "초기화"));
        assertTrue(jobs.existsById(job.getId()));
        reset.start(preview.token(), "시험 데이터 초기화");
        var result = awaitReset();
        assertEquals("COMPLETED", result.state(), result.message());
        assertFalse(guard.resetting());
        assertFalse(jobs.existsById(job.getId()));
        assertEquals(policy, management.settings());
        assertEquals(0, jdbc.queryForObject("select count(*) from test_session", Integer.class));
        assertNotNull(inputs.get(input.inputId()));
        assertTrue(Files.exists(inputPath));
        assertTrue(guard.deleted("DTN", job.getId()));
        assertThrows(RuntimeException.class, () -> receipts.capture(
                ("{\"testId\":\"" + job.getId() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json", false));
        assertThrows(RuntimeException.class, () -> reset.start(preview.token(), "시험 데이터 초기화"));
        guard.restoreRevision();
        assertTrue(guard.revision().endsWith(":TRIALS"));
        assertFalse(guard.revision().contains("\""));
    }

    @Test
    void serviceResetClearsOwnedMaterialsAndPresetsButKeepsUnknownSharedFiles() throws Exception {
        var input = inputs.create("reset.graw", 3, InputKind.GRAW_UPLOAD);
        inputs.append(input.inputId(), 0, new byte[] {4, 5, 6});
        DtnJob job = job(input.inputId());
        management.pin(key(job), true);
        Path root = directory.resolve("iq");
        Files.createDirectories(root);
        UUID owned = UUID.randomUUID(), external = UUID.randomUUID();
        Path bin = root.resolve(owned + ".bin"), foreign = root.resolve(external + ".bin");
        Files.write(bin, new byte[] {7, 8, 9});
        Files.write(foreign, new byte[] {10, 11});
        String owner = guard.storageOwner();
        Files.writeString(root.resolve(owned + ".context.json"), "{\"owner\":\"" + owner + "\"}");
        tx(() -> {
            DtnPreset preset = new DtnPreset();
            preset.setId(UUID.randomUUID());
            preset.setName("초기화 대상");
            preset.setSettingsJson("{}");
            preset.setUpdatedAt(Instant.now());
            em.persist(preset);
            var connection = new server.node.NodePeerSetting();
            connection.setId(1);
            connection.setBaseUrl("http://192.0.2.1:8088");
            em.merge(connection);
        });
        var preview = reset.preview(DataResetService.Mode.SERVICE);
        assertEquals(1L, preview.counts().get("IQ"));
        assertTrue(preview.retainedFiles() >= 1);
        assertTrue(preview.fileBytes() >= 6);
        assertTrue(Files.exists(bin));
        reset.start(preview.token(), "서비스 초기화");
        var result = awaitReset();
        assertEquals("COMPLETED", result.state(), result.message());
        assertFalse(Files.exists(bin));
        assertFalse(Files.exists(directory.resolve("files/inputs/" + input.inputId() + ".part")));
        assertTrue(Files.exists(foreign));
        assertEquals(0L, em.createQuery("select count(e) from DtnPreset e", Long.class).getSingleResult());
        assertEquals(0L, em.createQuery("select count(e) from InputBufferEntity e", Long.class).getSingleResult());
        assertEquals(0L, em.createQuery("select count(e) from NodePeerSetting e", Long.class).getSingleResult());
        assertFalse(management.settings().tests().enabled());
        assertTrue(guard.revision().endsWith(":SERVICE"));
        Files.delete(foreign);
    }

    @Test
    void resetRejectsWaitingTrialsChangedPreviewsAndConcurrentMutations() throws Exception {
        DtnJob job = job(null);
        var initial = reset.preview(DataResetService.Mode.TRIALS);
        DtnJob waiting = job(null);
        waiting.setState("WAITING_DTN");
        jobs.saveAndFlush(waiting);
        assertThrows(RuntimeException.class, () -> reset.start(initial.token(), "시험 데이터 초기화"));
        var blocked = reset.preview(DataResetService.Mode.TRIALS);
        assertFalse(blocked.blocked().isEmpty());
        assertThrows(RuntimeException.class, () -> reset.start(blocked.token(), "시험 데이터 초기화"));
        assertTrue(jobs.existsById(job.getId()));
        guard.resetting(true);
        try {
            mvc.perform(post("/lnis/api/v1/inputs").contentType("application/json").content("{}"))
                    .andExpect(status().isConflict());
            mvc.perform(get("/lnis/api/v1/data-management/reset/status")).andExpect(status().isOk());
            assertThrows(RuntimeException.class, () -> management.execute(management.preview(List.of(key(job))).token(), "TEST"));
        } finally {
            guard.resetting(false);
        }
        waiting.setCancelPending(true);
        waiting.setCancelWaitingOnly(true);
        jobs.saveAndFlush(waiting);
        var current = reset.preview(DataResetService.Mode.TRIALS);
        assertEquals(1, reset.closeWaiting(current.token()).get("cancelled"));
        var ended = jobs.findById(waiting.getId()).orElseThrow();
        assertEquals("CANCELLED", ended.getState());
        assertFalse(Boolean.TRUE.equals(ended.getCancelPending()));
        assertTrue(jobs.existsById(job.getId()));
    }

    @Test
    void failedFileResetIsResumableAndDoesNotEraseDatabaseEarly() throws Exception {
        Path root = directory.resolve("iq");
        Files.createDirectories(root);
        UUID id = UUID.randomUUID();
        Path file = root.resolve(id + ".bin").toAbsolutePath().normalize();
        Files.write(file, new byte[] {1, 2, 3});
        String owner = guard.storageOwner();
        Files.writeString(root.resolve(id + ".context.json"), "{\"owner\":\"" + owner + "\"}");
        DtnJob job = job(null);
        var preview = reset.preview(DataResetService.Mode.SERVICE);
        doThrow(new java.io.IOException("simulated locked file")).when(management).deletePath(file);
        try {
            reset.start(preview.token(), "서비스 초기화");
            var failed = awaitReset();
            assertEquals("FAILED", failed.state());
            assertTrue(guard.resetting());
            assertTrue(jobs.existsById(job.getId()));
            assertTrue(Files.exists(file));
            reset.restore();
            assertEquals("FAILED", reset.status().state());
        } finally {
            doCallRealMethod().when(management).deletePath(file);
        }
        reset.retry(preview.token(), "서비스 초기화");
        var completed = awaitReset();
        assertEquals("COMPLETED", completed.state(), completed.message());
        assertFalse(guard.resetting());
        assertFalse(jobs.existsById(job.getId()));
        assertFalse(Files.exists(file));
    }

    @Test
    void resetHttpContractRequiresConfirmationAndReportsCommittedRevision() throws Exception {
        DtnJob job = job(null);
        var response = mvc.perform(post("/lnis/api/v1/data-management/reset/preview")
                        .contentType("application/json").content("{\"mode\":\"TRIALS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREVIEW"))
                .andExpect(jsonPath("$.counts.DTN").value(1))
                .andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("token").asText();
        mvc.perform(post("/lnis/api/v1/data-management/reset/execute").contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"confirmation\":\"틀린 문구\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(jobs.existsById(job.getId()));
        mvc.perform(post("/lnis/api/v1/data-management/reset/execute").contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"confirmation\":\"시험 데이터 초기화\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RUNNING"));
        assertEquals("COMPLETED", awaitReset().state());
        mvc.perform(get("/lnis/api/v1/data-management/reset/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(header().string("X-LNIS-Data-Revision", token + ":TRIALS"));
    }

    @Test
    void queryDeletionOfSharedTrialsKeepsFilesNotApprovedInThePreview() {
        var input = inputs.create("shared-query.graw", 3, InputKind.GRAW_UPLOAD);
        inputs.append(input.inputId(), 0, new byte[] {1, 2, 3});
        DtnJob first = job(input.inputId()), second = job(input.inputId());
        var preview = management.previewQuery(new QuerySelection(Kind.DTN, "", "", null, null));
        assertTrue(preview.querySelection());
        assertTrue(preview.items().stream().allMatch(row -> row.related().isEmpty()));
        var result = management.execute(preview.token(), "TEST_QUERY_SHARED");
        assertTrue(result.results().stream().allMatch(row -> row.status().equals("DELETED")));
        assertFalse(jobs.existsById(first.getId()));
        assertFalse(jobs.existsById(second.getId()));
        assertNotNull(inputs.get(input.inputId()));
        assertTrue(Files.exists(directory.resolve("files/inputs/" + input.inputId() + ".part")));
    }

}
