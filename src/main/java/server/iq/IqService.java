package server.iq;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import server.common.DtnModels.IqFile;
import server.common.Hashing;
import server.dtn.DtnLogService;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** One on-demand native process. Shared files stay outside the DB and HTTP body. */
@Service
public class IqService {
    public static final long EXPECTED_BYTES = 90L * 12_000_000 * 2;
    private final ObjectMapper json;
    private final Path root, simulator;
    private final String hostDirectory;
    private final boolean enabled;
    private volatile Process process;
    private UUID active;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DtnLogService logs;
    private void trace(UUID id, String stage, boolean detail, String message) {
        if (logs != null) {
            logs.add(id, "IQ", stage, detail, message);
        }
    }
    private final Map<UUID, Map<String, Object>> jobs = new LinkedHashMap<>();
        public IqService(
            ObjectMapper json,
            String root,
            String simulator,
            boolean enabled) {
        this(json, root, null, simulator, enabled);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IqService(
            ObjectMapper json,
            @Value("${lnis.iq.directory:/exchange}") String root,
            @Value("${lnis.iq.host-path:${lnis.iq.host-directory:}}") String hostDirectory,
            @Value("${lnis.iq.simulator:/app/iq/afs_sim}") String simulator,
            @Value("${lnis.iq.enabled:false}") boolean enabled) {
        this.json = json;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.hostDirectory =
                hostDirectory == null || hostDirectory.isBlank() ? null : hostDirectory.trim();
        this.simulator = Path.of(simulator).toAbsolutePath().normalize();
        this.enabled = enabled;
    }
    public static String extractFileName(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        int lastBackslash = rawPath.lastIndexOf('\\');
        int lastSlash = rawPath.lastIndexOf('/');
        int lastSep = Math.max(lastBackslash, lastSlash);
        return lastSep >= 0 ? rawPath.substring(lastSep + 1) : rawPath;
    }
    public String formatFilePath(UUID id) {
        if (hostDirectory != null && !hostDirectory.isBlank()) {
            String clean = hostDirectory.trim();
            String sep = clean.contains("\\") ? "\\" : "/";
            if (clean.endsWith("/") || clean.endsWith("\\")) {
                return clean + id + ".bin";
            }
            return clean + sep + id + ".bin";
        }
        return "/exchange/" + id + ".bin";
    }
    public synchronized boolean managementBusy(UUID id) {
        return active != null && (id == null || active.equals(id));
    }
    public boolean enabled() {
        return enabled && Files.isExecutable(simulator);
    }
    public synchronized List<Map<String, Object>> recent() throws IOException {
        Map<UUID, Instant> updated = new HashMap<>();
        jobs.forEach((id, job) -> updated.put(id,
                Instant.parse((String) job.getOrDefault("updatedAt", Instant.EPOCH.toString()))));
        if (Files.isDirectory(root)) {
            try (var files = Files.list(root)) {
                for (Path file : files.filter(p -> p.getFileName().toString()
                        .matches("[0-9a-f-]{36}\\.json")).toList()) {
                    try {
                        UUID id = UUID.fromString(file.getFileName().toString().substring(0, 36));
                        Instant modified = Files.getLastModifiedTime(file).toInstant();
                        updated.merge(id, modified, (memory, stored) -> memory.isAfter(stored) ? memory : stored);
                    } catch (IOException | IllegalArgumentException ignored) {
                        /* 삭제 중이거나 잘못된 저장 정보는 목록에서 제외한다. */
                    }
                }
            }
        }
        var order = Comparator.comparing((UUID id) -> !id.equals(active))
                .thenComparing(updated::get, Comparator.reverseOrder())
                .thenComparing(UUID::toString);
        List<Map<String, Object>> result = new ArrayList<>();
        for (UUID id : updated.keySet().stream().sorted(order).toList()) {
            try {
                result.add(status(id));
            } catch (IOException | IllegalArgumentException error) {
                if (jobs.containsKey(id)) {
                    result.add(Map.of("id", id, "state", "FAILED", "message", "파일 조회 실패"));
                }
            }
            if (result.size() == 50) break;
        }
        return result;
    }
    public synchronized void delete(UUID id) throws IOException {
        if (id.equals(active)) {
            throw new IllegalStateException("생성 중인 파일은 취소 후 삭제하세요.");
        }
        Path file = root.resolve(id + ".bin");
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("심볼릭 링크는 삭제할 수 없습니다.");
        }
        Files.deleteIfExists(file);
        Files.deleteIfExists(root.resolve(id + ".json"));
        Files.deleteIfExists(root.resolve(id + ".context.json"));
        jobs.remove(id);
    }
    public synchronized Map<String, Object> start() throws IOException {
        if (!enabled()) {
            throw new IllegalStateException("I/Q 생성기를 설정하세요.");
        }
        throw new IllegalArgumentException("GNSS 입력을 선택하세요.");
    }
    public static String earthInput(List<byte[]> records, server.common.DtnModels.Pvt pvt) {
        return earthInput(records, pvt, message -> {});
    }

    public static String earthInput(List<byte[]> records, server.common.DtnModels.Pvt pvt,
            java.util.function.Consumer<String> report) {
        if (pvt == null || !pvt.isPositionValid() || !pvt.isVelocityValid()) {
            throw new IllegalArgumentException("유효 PVT 필요");
        }
        int epochIndex = -1;
        for (int i = 0; i < records.size(); i++) {
            var message = server.gnss.GrawCodec.decode(records.get(i)).message();
            if (message instanceof server.gnss.GrawCodec.ObservationEpoch epoch
                    && epoch.week() == pvt.getWeek()
                    && Double.compare(epoch.receiverTowSeconds(), pvt.getTowSeconds()) == 0) {
                epochIndex = i;
                break;
            }
        }
        if (epochIndex < 0) {
            throw new IllegalArgumentException("PVT 관측 시각 불일치");
        }
        var epoch = new server.pvt.DtnDelay.Epoch(epochIndex, pvt.getWeek(), pvt.getTowSeconds());
        var selected = server.gnss.GrawCodec.splitLengthPrefixed(
                server.pvt.DtnDelay.select(records, epoch));
        var frames = server.afs.AfsPvtFrameCodec.select(selected);
        var excluded = frames.stream()
                .filter(p -> p.prn() != 0 && p.navigation() == null)
                .map(p -> p.prn()).toList();
        if (!excluded.isEmpty()) {
            report.accept("I/Q 생성 제외 PRN " + excluded + " · 선행 GPS LNAV 서브프레임 1·2·3 미확보 또는 불일치");
        }
        var ready = frames.stream().filter(p -> p.prn() != 0 && p.navigation() != null).toList();
        if (ready.size() < 4) {
            throw new IllegalArgumentException("I/Q 생성 불가 · 관측값과 선행 GPS LNAV가 준비된 위성 "
                    + ready.size() + "개 · 최소 4개 필요");
        }
        report.accept("I/Q 생성 대상 PRN " + ready.stream().map(p -> p.prn()).toList());
        var text = new StringBuilder("LNIS-IQ-EARTH-2 ")
                .append(pvt.getWeek()).append(' ').append(pvt.getTowSeconds());
        for (double value : pvt.getEcefMeters()) {
            text.append(' ').append(value);
        }
        for (double value : pvt.getVelocityMetersPerSecond()) {
            text.append(' ').append(value);
        }
        text.append(' ').append(pvt.getReceiverClockBiasSeconds()).append('\n');
        // Native navigation, selected PRNs and RF frames must use the same coherent snapshot.
        for (var payload : ready) {
            for (int[] words : payload.navigation()) {
                text.append("N ").append(payload.prn());
                for (int word : words) {
                    text.append(' ').append(word);
                }
                text.append('\n');
            }
            text.append("P ").append(payload.prn()).append('\n');
            var blocks = server.afs.AfsPvtFrameCodec.encode(payload);
            text.append("F ").append(payload.prn());
            for (byte[] bits : List.of(blocks.sb2(), blocks.sb3(), blocks.sb4())) {
                text.append(' ');
                for (byte bit : bits) {
                    text.append((char) ('0' + bit));
                }
            }
            text.append('\n');
        }
        return text.toString();
    }
    public synchronized Map<String, Object> start(String earthInput) throws IOException {
        return start(earthInput, null);
    }
    public synchronized Map<String, Object> start(String earthInput, UUID inputId)
            throws IOException {
        if (earthInput == null
                || !(earthInput.startsWith("LNIS-IQ-EARTH-1 ")
                        || earthInput.startsWith("LNIS-IQ-EARTH-2 "))) {
            throw new IllegalArgumentException("GNSS 입력 필요");
        }
        if (!enabled()) {
            throw new IllegalStateException("I/Q 생성기를 설정하세요.");
        }
        if (active != null) {
            throw new IllegalStateException("I/Q 생성 작업 진행 중");
        }
        Files.createDirectories(root);
        if (Files.getFileStore(root).getUsableSpace() < EXPECTED_BYTES + 268_435_456L) {
            throw new IllegalStateException("I/Q 생성 공간 부족: 최소 2.43 GB가 필요합니다.");
        }
        UUID id = UUID.randomUUID();
        if (logs != null) {
            logs.copy(inputId, id, "IQ");
        }
        trace(
                id,
                "I/Q 입력",
                true,
                "대상 PRN "
                        + earthInput.lines().filter(s -> s.startsWith("P ")).toList()
                        + " · 항법 레코드 "
                        + earthInput.lines().filter(s -> s.startsWith("N ")).count()
                        + "건");
        active = id;
        set(id, "GENERATING", "GNSS 기반 90초 AFS I/Q 생성 중 · PRN별 SB2·SB3·SB4 반복", null);
        Thread.ofVirtual().name("iq-generate").start(() -> generate(id, earthInput));
        return status(id);
    }
    private void generate(UUID id, String earthInput) {
        Path work = root.resolve("work-" + id), part = root.resolve(id + ".bin.part");
        try {
            Files.createDirectory(work);
            Files.writeString(work.resolve("earth-input.txt"), earthInput);
            for (String asset : List.of("008_Weil1500hex210prns.txt")) {
                Files.copy(simulator.getParent().resolve(asset), work.resolve(asset));
            }
            synchronized (this) {
                if (!id.equals(active) || cancelled(id)) {
                    return;
                }
                process =
                        new ProcessBuilder(
                                        simulator.toString(),
                                        "-e",
                                        work.resolve("earth-input.txt").toString(),
                                        "-t",
                                        "90",
                                        "-s",
                                        "12000000",
                                        "-b",
                                        "2",
                                        part.toString())
                                .directory(work.toFile())
                                .redirectErrorStream(true)
                                .redirectOutput(work.resolve("generation.log").toFile())
                                .start();
            }
            trace(id, "I/Q 생성", true, "기존 AFS 생성기 실행 · 90초 · 12 MHz · I/Q signed int8");
            Process running = process;
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30),
                    started = System.nanoTime();
            int previous = 0;
            while (!running.waitFor(2, TimeUnit.SECONDS)) {
                if (System.nanoTime() > deadline) {
                    running.destroyForcibly();
                    throw new IOException("I/Q 생성 제한 시간 초과");
                }
                long written = Files.exists(part) ? Files.size(part) : 0;
                int bucket = (int) Math.min(9, written * 10 / EXPECTED_BYTES);
                if (bucket > previous) {
                    previous = bucket;
                    trace(
                            id,
                            "I/Q 생성",
                            true,
                            "파일 출력 "
                                    + (bucket * 10)
                                    + "% · "
                                    + written
                                    + " / "
                                    + EXPECTED_BYTES
                                    + " bytes");
                }
            }
            synchronized (this) {
                if (!id.equals(active) || cancelled(id)) {
                    return;
                }
            }
            long bytes = Files.isRegularFile(part) ? Files.size(part) : 0;
            if (running.exitValue() != 0 || bytes != EXPECTED_BYTES) {
                throw new IOException(generationFailure(running.exitValue(), bytes,
                        work.resolve("generation.log")));
            }
            trace(
                    id,
                    "I/Q 검증",
                    true,
                    "파일 출력 100% · 크기 확인 완료 · "
                            + Files.size(part)
                            + " bytes · 생성 소요 "
                            + ((System.nanoTime() - started) / 1_000_000)
                            + " ms");
            String digest = sha256(part, EXPECTED_BYTES, progress -> {
                synchronized (this) {
                    if (!id.equals(active) || cancelled(id)) {
                        throw new java.util.concurrent.CancellationException("검증 취소");
                    }
                    var job = jobs.get(id);
                    job.put("phase", "VERIFYING");
                    job.put("verificationBytes", progress.checkedBytes());
                    job.put("verificationTotalBytes", progress.totalBytes());
                    job.put("verificationElapsedMillis", progress.elapsedMillis());
                    job.put("message", progress.message());
                    job.put("updatedAt", Instant.now().toString());
                }
                trace(id, "I/Q 검증", true, progress.message());
            });
            var context = IqReceiver.source(earthInput, digest);
            trace(id, "I/Q 검증", true, "SHA-256 계산 완료 · " + digest);
            synchronized (this) {
                if (!id.equals(active) || cancelled(id)) {
                    return;
                }
                Files.move(part, root.resolve(id + ".bin"), StandardCopyOption.ATOMIC_MOVE);
                IqFile file =
                        new IqFile(
                                formatFilePath(id),
                                EXPECTED_BYTES,
                                digest,
                                90,
                                12_000_000,
                                "IQ_INTERLEAVED_INT8",
                                2);
                json.writeValue(root.resolve(id + ".context.json").toFile(), context);
                // The file manifest marks READY after its PVT context has been persisted.
                json.writeValue(root.resolve(id + ".json").toFile(), file);
                set(id, "READY", "파일 생성·크기·SHA-256 검증 완료", file);
            }
        } catch (Exception error) {
            synchronized (this) {
                if (id.equals(active) && !cancelled(id)) {
                    set(id, "FAILED", error.getMessage(), null);
                }
            }
        } finally {
            synchronized (this) {
                if (id.equals(active)) {
                    active = null;
                    process = null;
                }
            }
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
        }
    }
    static String generationFailure(int exit, long bytes, Path log) {
        String detail;
        try (var input = new RandomAccessFile(log.toFile(), "r")) {
            input.seek(Math.max(0, input.length() - 4096));
            byte[] tail = new byte[(int) (input.length() - input.getFilePointer())];
            input.readFully(tail);
            detail = new String(tail, java.nio.charset.StandardCharsets.UTF_8).strip();
            if (detail.isEmpty()) {
                detail = "생성기 로그 없음";
            }
        } catch (IOException error) {
            detail = "생성기 로그 조회 실패: " + error.getClass().getSimpleName();
        }
        return "I/Q 생성 실패 · 종료 코드 " + exit + " · 출력 " + bytes + " / "
                + EXPECTED_BYTES + " bytes · 생성기: " + detail;
    }

    public synchronized Map<String, Object> status(UUID id) throws IOException {
        Map<String, Object> job = jobs.get(id);
        Path metadata = root.resolve(id + ".json");
        if (job == null && Files.isRegularFile(metadata)) {
            Instant storedAt = Files.getLastModifiedTime(metadata).toInstant();
            IqFile file = json.readValue(metadata.toFile(), IqFile.class);
            set(id, "READY", "저장된 I/Q 파일", file);
            job = jobs.get(id);
            job.put("updatedAt", storedAt.toString());
        }
        if (job == null) {
            throw new IllegalArgumentException("I/Q 작업을 찾을 수 없습니다.");
        }
        var result = new LinkedHashMap<>(job);
        Path part = root.resolve(id + ".bin.part");
        // 취소/완료 처리 중 .part가 삭제·이동될 수 있다. exists→size 사이 경합도 정상 상태다.
        long generated = "READY".equals(job.get("state")) ? EXPECTED_BYTES : 0;
        try {
            generated = Files.size(part);
        } catch (NoSuchFileException ignored) {
            /* 아직 생성 전이거나 이미 취소/완료됨 */
        }
        result.put("generatedBytes", generated);
        result.put("expectedBytes", EXPECTED_BYTES);
        if (job.get("file") instanceof IqFile file && "READY".equals(job.get("state"))) {
            try {
                Path context = root.resolve(id + ".context.json");
                var source = Files.isRegularFile(context)
                        ? json.readValue(context.toFile(), IqReceiver.Source.class) : null;
                if (source == null || source.metadata() == null
                        || !IqReceiver.FRAME_METHOD.equals(source.metadata().pvtMethod())) {
                    result.put("state", "REGENERATE");
                    result.put("message", "현재 프레임 규격으로 다시 생성하세요.");
                } else {
                    result.put("preview", preview(file));
                }
            } catch (IOException | IllegalArgumentException error) {
                result.put("state", "FAILED");
                result.put("message", "저장된 파일이 없거나 읽을 수 없습니다.");
            }
        }
        return result;
    }
    public synchronized void cancel(UUID id) {
        if (!id.equals(active)) {
            return;
        }
        // Keep the slot until the worker has reaped the native process.
        if (process != null) {
            process.destroyForcibly();
        }
        set(id, "CANCELLED", "생성 취소", null);
    }
    private boolean cancelled(UUID id) {
        return jobs.containsKey(id) && "CANCELLED".equals(jobs.get(id).get("state"));
    }
    private void set(UUID id, String state, String message, IqFile file) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", id);
        value.put("state", state);
        value.put("message", message);
        value.put("updatedAt", Instant.now().toString());
        if (file != null) {
            value.put("file", file);
        }
        jobs.put(id, value);
        if (logs != null && !("READY".equals(state) && "저장된 I/Q 파일".equals(message))) {
            logs.add(
                    id,
                    "IQ",
                    "FAILED".equals(state) ? "ERROR" : "CANCELLED".equals(state) ? "WARN" : "INFO",
                    "I/Q",
                    false,
                    message);
        }
        if (jobs.size() > 50) {
            jobs.keySet().stream().filter(key -> !key.equals(active)).findFirst().ifPresent(jobs::remove);
        }
    }
    public IqFile completed(UUID id) throws IOException {
        var job = status(id);
        if (!"READY".equals(job.get("state"))) {
            throw new IllegalStateException("완료된 I/Q 파일만 전송할 수 있습니다.");
        }
        IqFile file = (IqFile) job.get("file");
        // Reuse the generation digest; the receiver still verifies the complete file.
        checkedPath(file);
        String clientPath = formatFilePath(id);
        if (!clientPath.equals(file.filePath())) {
            file =
                    new IqFile(
                            clientPath,
                            file.sizeBytes(),
                            file.sha256(),
                            file.durationSeconds(),
                            file.sampleRateHz(),
                            file.sampleFormat(),
                            file.quantizationBits());
        }
        return file;
    }
    /** Old files remain file-integrity-only; never invent missing generation metadata. */
    public IqReceiver.Source source(UUID id, IqFile file) throws IOException {
        Path context = root.resolve(id + ".context.json");
        if (!Files.exists(context)) {
            return null;
        }
        if (Files.isSymbolicLink(context) || Files.size(context) > 1_048_576) {
            throw new IOException("I/Q 생성 메타데이터 경로·크기 오류");
        }
        var source = json.readValue(context.toFile(), IqReceiver.Source.class);
        if (!file.sha256().equals(source.sha256())) {
            throw new IOException("I/Q 파일과 생성 메타데이터 불일치");
        }
        IqReceiver.validate(source.metadata());
        return source;
    }
    public Path path(IqFile file) throws IOException {
        if (file == null || file.filePath() == null) {
            throw new IllegalArgumentException("허용되지 않은 I/Q 공유 경로");
        }
        String name = extractFileName(file.filePath());
        if (name == null || !name.matches("[0-9a-fA-F-]{36}\\.bin")) {
            throw new IllegalArgumentException("허용되지 않은 I/Q 공유 경로: " + file.filePath());
        }
        UUID.fromString(name.substring(0, 36));
        Path path = root.resolve(name);
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().getParent().equals(root.toRealPath())) {
            throw new IllegalArgumentException("완료된 공유 I/Q 파일이 없습니다.");
        }
        return path;
    }
    /** A confirmed size/hash mismatch is distinct from a missing file or decoder failure. */
    public static class FileMismatchException extends IllegalArgumentException {
        public FileMismatchException(String message) { super(message); }
    }

    public Map<String, Object> verify(IqFile file) throws IOException {
        return verify(file, progress -> {});
    }

    public Map<String, Object> verify(IqFile file,
            java.util.function.Consumer<VerificationProgress> report) throws IOException {
        Path path = checkedPath(file);
        if (!sha256(path, file.sizeBytes(), report).equals(file.sha256())) {
            throw new FileMismatchException("I/Q SHA-256 불일치");
        }
        return Map.of(
                "verdict",
                "PASS",
                "sizeBytes",
                file.sizeBytes(),
                "sha256",
                file.sha256(),
                "filePath",
                file.filePath());
    }
    private Path checkedPath(IqFile file) throws IOException {
        if (file == null
                || file.sizeBytes() != EXPECTED_BYTES
                || file.durationSeconds() != 90
                || file.sampleRateHz() != 12_000_000
                || file.quantizationBits() != 2
                || !"IQ_INTERLEAVED_INT8".equals(file.sampleFormat())
                || file.sha256() == null
                || !file.sha256().matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("I/Q 파일 메타데이터 오류");
        }
        Path path = path(file);
        if (Files.size(path) != file.sizeBytes()) {
            throw new FileMismatchException("I/Q 파일 크기 불일치");
        }
        return path;
    }

    public List<List<Integer>> preview(IqFile file) throws IOException {
        try (var in = Files.newInputStream(path(file))) {
            byte[] bytes = in.readNBytes(64);
            var values = new ArrayList<List<Integer>>();
            for (int i = 0; i + 1 < bytes.length; i += 2) {
                values.add(List.of((int) bytes[i], (int) bytes[i + 1]));
            }
            return values;
        }
    }
    public record VerificationProgress(long checkedBytes, long totalBytes, long elapsedMillis) {
        public String message() {
            long percent = Math.min(100, checkedBytes * 100 / Math.max(1, totalBytes));
            double megabytesPerSecond = checkedBytes / 1_000_000.0 / Math.max(.001, elapsedMillis / 1000.0);
            return String.format(Locale.ROOT,
                    "SHA-256 검증 %d%% · %.2f / %.2f GB · 경과 %d초 · %.1f MB/s",
                    percent, checkedBytes / 1_000_000_000.0, totalBytes / 1_000_000_000.0,
                    elapsedMillis / 1000, megabytesPerSecond);
        }
    }

    static String sha256(Path path, long totalBytes,
            java.util.function.Consumer<VerificationProgress> report) throws IOException {
        var digest = Hashing.sha256Digest();
        long started = System.nanoTime(), reported = started, checked = 0;
        report.accept(new VerificationProgress(0, totalBytes, 0));
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("검증 중단");
                }
                digest.update(buffer, 0, count);
                checked += count;
                long now = System.nanoTime();
                if (now - reported >= TimeUnit.SECONDS.toNanos(5)) {
                    report.accept(new VerificationProgress(checked, totalBytes,
                            (now - started) / 1_000_000));
                    reported = now;
                }
            }
        }
        if (checked != totalBytes) {
            throw new IOException("I/Q 검증 중 파일 크기 변경 · " + checked + " / " + totalBytes + " bytes");
        }
        String hash = Hashing.hex(digest.digest());
        report.accept(new VerificationProgress(checked, totalBytes,
                (System.nanoTime() - started) / 1_000_000));
        return hash;
    }
    @PreDestroy
    public synchronized void close() {
        if (process != null) {
            process.destroyForcibly();
        }
    }
}
