package server.management;

import static server.management.DataManagementService.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import server.config.StorageProperties;
import server.dtn.DtnService;
import server.iq.IqService;
import server.node.LocalNodeLifecycle;
import server.node.NodeConnectionService;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 현재 서비스의 자료만 초기화한다. 배포 설정과 외부 공유 자료는 유지한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataResetService {
    private final DataManagementService management;
    private final DataManagementGuard guard;
    private final EntityManager em;
    private final ObjectMapper json;
    private final StorageProperties storage;
    private final DtnService dtn;
    private final IqService iq;
    private final ObjectProvider<NodeConnectionService> connection;
    private final ObjectProvider<LocalNodeLifecycle> runtime;

    @Value("${lnis.node.role:server}") private String role;
    @Value("${lnis.iq.directory:/exchange}") private String iqDirectory;
    private volatile Thread worker;

    public enum Mode { TRIALS, SERVICE }
    public record FileTarget(Kind kind, String name, long bytes, Instant modifiedAt) {}
    public record Plan(UUID token, Mode mode, String role, Instant expiresAt,
            List<UUID> trials, List<UUID> receiptTests, List<UUID> inputs,
            List<FileTarget> files, Map<String, Long> counts,
            int retainedFiles, List<String> blocked, DtnService.WaitingSummary waiting) {}
    public record Job(Plan plan, String state, int completed, String message, Instant updatedAt) {}
    public record View(UUID token, Mode mode, String role, String state, Instant expiresAt,
            Map<String, Long> counts, long fileBytes, int retainedFiles,
            List<String> blocked, DtnService.WaitingSummary waiting,
            int completed, int total, String message, Instant updatedAt) {}

    @PostConstruct
    void restore() {
        String owner = management.stored("STORAGE_OWNER", String.class);
        if (owner == null) {
            owner = UUID.randomUUID().toString();
            String assigned = owner;
            management.transaction(() -> { management.put("STORAGE_OWNER", "STORAGE_OWNER", assigned); return null; });
        }
        guard.storageOwner(owner);
        Job job = management.stored("RESET_JOB", Job.class);
        if (job != null && !job.state().equals("COMPLETED")) {
            guard.resetting(true);
            saveJob(job.plan(), "FAILED", job.completed(), "초기화가 중단되었습니다. 남은 항목을 확인하고 다시 실행하세요.");
        }
    }

    public View status() {
        Job job = management.stored("RESET_JOB", Job.class);
        return job == null ? null : view(job);
    }

    public View preview(Mode mode) {
        if (mode == null) throw new IllegalArgumentException("초기화 범위가 필요합니다.");
        guard.requireOperational();
        var lock = guard.gate.readLock();
        lock.lock();
        try {
            Plan plan = plan(mode, UUID.randomUUID(), Instant.now().plusSeconds(600));
            management.transaction(() -> {
                management.put("RESET_PREVIEW:" + plan.token(), "RESET_PREVIEW", plan);
                return null;
            });
            return view(new Job(plan, "PREVIEW", 0, "", Instant.now()));
        } finally {
            lock.unlock();
        }
    }

    public synchronized View start(UUID token, String confirmation) {
        guard.requireOperational();
        return management.maintenance(() -> {
            Plan plan = management.stored("RESET_PREVIEW:" + token, Plan.class);
            if (plan == null || plan.expiresAt().isBefore(Instant.now()))
                throw conflict("초기화 대상 확인이 만료됐습니다. 다시 확인하세요.");
            if (!role.equals(plan.role())) throw conflict("서비스 역할이 변경됐습니다. 초기화 대상을 다시 확인하세요.");
            confirm(plan.mode(), confirmation);
            Plan current = plan(plan.mode(), plan.token(), plan.expiresAt());
            if (!sameTargets(plan, current)) throw conflict("초기화 대상이 변경됐습니다. 다시 확인하세요.");
            if (!current.blocked().isEmpty()) throw conflict(String.join(" · ", current.blocked()));
            guard.resetting(true);
            try {
                Job job = saveJob(plan, "RUNNING", 0, "초기화 준비 중");
                launch(job);
                return view(job);
            } catch (RuntimeException error) {
                guard.resetting(false);
                throw error;
            }
        });
    }

    public Map<String, Integer> closeWaiting(UUID token) {
        guard.requireOperational();
        return management.maintenance(() -> {
            Plan plan = management.stored("RESET_PREVIEW:" + token, Plan.class);
            if (plan == null || plan.expiresAt().isBefore(Instant.now()))
                throw conflict("초기화 대상을 다시 확인하세요.");
            int closed = management.transaction(() -> {
                int count = 0;
                for (UUID id : plan.waiting().testIds())
                    count += em.createQuery("update DtnJob e set e.state='CANCELLED', e.cancelPending=false,"
                                    + " e.cancelWaitingOnly=false, e.updatedAt=:now,"
                                    + " e.message='초기화 준비 · 현재 서비스의 수신 대기 종료'"
                                    + " where e.id=:id and e.state='WAITING_DTN'")
                            .setParameter("now", Instant.now()).setParameter("id", id).executeUpdate();
                return count;
            });
            log.info("DATA_RESET_WAITING_CLOSED token={} count={} role={}", token, closed, role);
            return Map.of("cancelled", closed, "skipped", plan.waiting().count() - closed, "pending", 0);
        });
    }

    public synchronized View retry(UUID token, String confirmation) {
        Job job = management.stored("RESET_JOB", Job.class);
        if (job == null || !job.plan().token().equals(token) || !job.state().equals("FAILED"))
            throw conflict("다시 실행할 초기화 작업이 없습니다.");
        if (!role.equals(job.plan().role())) throw conflict("초기화 당시의 서비스 역할로 다시 실행하세요.");
        confirm(job.plan().mode(), confirmation);
        return management.maintenance(() -> {
            Job next = saveJob(job.plan(), "RUNNING", job.completed(), "남은 항목 초기화 중");
            guard.resetting(true);
            launch(next);
            return view(next);
        });
    }

    private void confirm(Mode mode, String confirmation) {
        String expected = mode == Mode.SERVICE ? "서비스 초기화" : "시험 데이터 초기화";
        if (!expected.equals(confirmation)) throw new IllegalArgumentException(expected + "를 정확히 입력하세요.");
    }

    private boolean sameTargets(Plan first, Plan next) {
        return first.trials().equals(next.trials()) && first.receiptTests().equals(next.receiptTests())
                && first.inputs().equals(next.inputs()) && first.files().equals(next.files())
                && first.counts().equals(next.counts()) && first.retainedFiles() == next.retainedFiles();
    }

    private long count(String entity) {
        return em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult();
    }

    private Plan plan(Mode mode, UUID token, Instant expiresAt) {
        List<UUID> trials = em.createQuery("select e.id from DtnJob e order by e.id", UUID.class).getResultList();
        List<UUID> receiptTests = em.createQuery(
                "select distinct e.testId from DtnReceipt e where e.testId is not null order by e.testId",
                UUID.class).getResultList();
        List<UUID> inputs = mode == Mode.SERVICE ? em.createQuery(
                "select e.inputId from InputBufferEntity e order by e.inputId", UUID.class).getResultList() : List.of();
        var counts = new LinkedHashMap<String, Long>();
        counts.put("DTN", (long) trials.size());
        counts.put("RECEIPT", count("DtnReceipt"));
        counts.put("LOG", count("DtnLogEntry"));
        counts.put("ARCHIVE", archiveCount());
        counts.put("INPUT", (long) inputs.size());
        counts.put("PRESET", mode == Mode.SERVICE ? count("DtnPreset") : 0);
        counts.put("PIN", em.createQuery("select count(e) from ManagementEntry e where e.kind='PIN'"
                + (mode == Mode.SERVICE ? "" : " and (e.id like 'PIN:DTN:%' or e.id like 'PIN:RECEIPT:%')"),
                Long.class).getSingleResult());
        var files = new ArrayList<FileTarget>();
        int retained = 0;
        long iqCount = 0;
        if (mode == Mode.SERVICE) {
            Path inputRoot = storage.getDataDirectory().resolve("files/inputs").toAbsolutePath().normalize();
            // This directory is service-local; only generated UUID filenames belong to the reset.
            try {
                if (Files.isSymbolicLink(inputRoot)) throw new IOException("입력 저장 경로가 링크입니다.");
                if (Files.isDirectory(inputRoot)) {
                    try (var paths = Files.list(inputRoot)) {
                        for (Path path : paths.sorted().toList()) {
                            String name = path.getFileName().toString();
                            if (name.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(graw|part|ubx|ubx\\.part)"))
                                files.add(target(Kind.INPUT, path));
                        }
                    }
                }
                for (Row row : management.iqRows()) {
                    if (!ownedIq(row.key().id())) { retained++; continue; }
                    iqCount++;
                    for (Path path : management.iqPaths(row.key().id())) {
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) files.add(target(Kind.IQ, path));
                    }
                }
            } catch (IOException error) {
                throw new IllegalStateException("초기화 대상 파일 확인 실패", error);
            }
        }
        counts.put("IQ", iqCount);
        var blocked = new ArrayList<String>();
        List<UUID> waitingIds = em.createQuery(
                "select e.id from DtnJob e where e.state='WAITING_DTN' order by e.id", UUID.class).getResultList();
        DtnService.WaitingSummary waiting = new DtnService.WaitingSummary(waitingIds.size(), Instant.now(), waitingIds);
        if (waiting.count() > 0) blocked.add("수신 대기 " + waiting.count() + "건을 먼저 종료하세요.");
        long active = em.createQuery(
                "select count(e) from DtnJob e where e.state not in ('COMPLETED','FAILED','CANCELLED','INCONCLUSIVE')",
                Long.class).getSingleResult();
        if (active > waiting.count() || dtn.managementBusy() || iq.managementBusy(null))
            blocked.add("시험·수집·파일 처리 완료 후 실행하세요.");
        files.sort(Comparator.comparing((FileTarget target) -> target.kind().name()).thenComparing(FileTarget::name));
        return new Plan(token, mode, role, expiresAt, trials, receiptTests, inputs, files,
                counts, retained, blocked, waiting);
    }

    private boolean ownedIq(UUID id) {
        Path context = Path.of(iqDirectory).resolve(id + ".context.json");
        try {
            if (!Files.isRegularFile(context, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(context))
                return false;
            var metadata = json.readTree(context.toFile());
            return metadata != null && Objects.equals(guard.storageOwner(), metadata.path("owner").asText());
        } catch (IOException invalid) {
            return false;
        }
    }

    private long archiveCount() {
        long tables = ((Number) em.createNativeQuery(
                "select count(*) from information_schema.tables where table_schema=current_schema() and table_name='TEST_SESSION'")
                .getSingleResult()).longValue();
        return tables == 0 ? 0 : ((Number) em.createNativeQuery("select count(*) from test_session")
                .getSingleResult()).longValue();
    }

    private FileTarget target(Kind kind, Path path) throws IOException {
        validatePath(root(kind), path);
        return new FileTarget(kind, path.getFileName().toString(), management.size(path),
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant());
    }

    private Path root(Kind kind) {
        return (kind == Kind.INPUT ? storage.getDataDirectory().resolve("files/inputs")
                : Path.of(iqDirectory)).toAbsolutePath().normalize();
    }

    private void validatePath(Path root, Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (!path.getParent().equals(root) || Files.isSymbolicLink(root) || Files.isSymbolicLink(path))
            throw new IOException("초기화 허용 경로가 아닙니다.");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !path.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("저장소 외부 경로는 초기화할 수 없습니다.");
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(path)) {
                for (Path child : paths.toList())
                    if (Files.isSymbolicLink(child) || !child.toRealPath().startsWith(root.toRealPath()))
                        throw new IOException("링크가 포함된 작업 폴더는 초기화할 수 없습니다.");
            }
        }
    }

    private void launch(Job job) {
        worker = Thread.ofVirtual().name("data-reset").unstarted(() -> execute(job));
        worker.start();
    }

    private void execute(Job initial) {
        Plan plan = initial.plan();
        log.info("DATA_RESET_START id={} mode={} role={}", plan.token(), plan.mode(), role);
        try {
            management.maintenance(() -> {
                for (int index = initial.completed(); index < plan.files().size(); index++) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("초기화 중단");
                    FileTarget item = plan.files().get(index);
                    Path path = root(item.kind()).resolve(item.name()).normalize();
                    try {
                        validatePath(root(item.kind()), path);
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                    && !target(item.kind(), path).equals(item))
                                throw new IOException("확인 이후 파일이 변경됐습니다: " + item.name());
                            if (item.kind() == Kind.IQ) management.deletePath(path);
                            else Files.deleteIfExists(path);
                        }
                    } catch (IOException error) {
                        throw new IllegalStateException("파일 초기화 실패 · " + item.name() + " · " + error.getMessage(), error);
                    }
                    saveJob(plan, "RUNNING", index + 1, "파일 정리 중");
                }
                saveJob(plan, "RUNNING", plan.files().size(), "시험 기록·설정 정리 중");
                if (plan.mode() == Mode.SERVICE) {
                    LocalNodeLifecycle local = runtime.getIfAvailable();
                    if (local != null) local.resetGnssSettings();
                }
                management.transaction(() -> {
                    Set<UUID> ids = new HashSet<>(plan.trials());
                    ids.addAll(plan.receiptTests());
                    for (UUID id : ids) {
                        Key key = new Key(Kind.DTN, id);
                        management.put("DELETED:" + key.code(), "DELETED", key);
                    }
                    // 독립 AFS 화면 제거 후 남은 보관 이력도 명시적 초기화에 포함한다.
                    if (archiveCount() > 0) em.createNativeQuery("delete from test_session").executeUpdate();
                    for (String entity : List.of("DtnReceipt", "DtnLogEntry", "DtnJob", "RealtimeEventEntity"))
                        em.createQuery("delete from " + entity).executeUpdate();
                    if (plan.mode() == Mode.SERVICE) {
                        em.createQuery("delete from InputChunkEntity").executeUpdate();
                        em.createQuery("delete from InputBufferEntity").executeUpdate();
                        em.createQuery("delete from DtnPreset").executeUpdate();
                        em.createQuery("delete from ManagementEntry e where e.kind in ('PIN','SETTINGS','OP','PREVIEW','POLICY','RESET_PREVIEW')")
                                .executeUpdate();
                        em.createQuery("delete from NodePeerSetting").executeUpdate();
                        NodeConnectionService settings = connection.getIfAvailable();
                        if (settings != null) settings.reset();
                    } else {
                        em.createQuery("delete from ManagementEntry e where e.kind in ('OP','PREVIEW','POLICY','RESET_PREVIEW')"
                                + " or (e.kind='PIN' and (e.id like 'PIN:DTN:%' or e.id like 'PIN:RECEIPT:%'))").executeUpdate();
                    }
                    String revision = plan.token() + ":" + plan.mode();
                    management.put("RESET_REVISION", "RESET_REVISION", revision);
                    management.put("RESET_JOB", "RESET_JOB", new Job(plan, "COMPLETED",
                            plan.files().size() + 1, "초기화 완료", Instant.now()));
                    return null;
                });
                if (plan.mode() == Mode.SERVICE) iq.forgetJobs();
                // Publish only after commit, so other browsers never clear settings for a failed reset.
                guard.revision(plan.token() + ":" + plan.mode());
                guard.resetting(false);
                return null;
            });
            log.info("DATA_RESET_COMPLETED id={} mode={}", plan.token(), plan.mode());
        } catch (Exception error) {
            Job current = management.stored("RESET_JOB", Job.class);
            saveJob(plan, "FAILED", current == null ? initial.completed() : current.completed(),
                    Objects.toString(error.getMessage(), "초기화 실패"));
            log.error("DATA_RESET_FAILED id={} mode={}", plan.token(), plan.mode(), error);
        }
    }

    private Job saveJob(Plan plan, String state, int completed, String message) {
        Job job = new Job(plan, state, completed, message, Instant.now());
        management.transaction(() -> { management.put("RESET_JOB", "RESET_JOB", job); return null; });
        return job;
    }

    private View view(Job job) {
        Plan plan = job.plan();
        return new View(plan.token(), plan.mode(), plan.role(), job.state(), plan.expiresAt(),
                plan.counts(), plan.files().stream().mapToLong(FileTarget::bytes).sum(), plan.retainedFiles(),
                plan.blocked(), plan.waiting(), job.completed(), plan.files().size() + 1, job.message(), job.updatedAt());
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    @PreDestroy
    void close() {
        Thread running = worker;
        if (running != null) running.interrupt();
    }
}
