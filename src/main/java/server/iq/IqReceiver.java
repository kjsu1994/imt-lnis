package server.iq;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import server.common.DtnModels.*;
import server.pvt.DtnComparison;
import server.pvt.NativePvtCodec;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** File-only PocketSDR tracking. Reference positions NEVER enter the native receiver/solver. */
@Service
@Slf4j
public class IqReceiver {
    public static final String METHOD = "AFS_IQ_GPS_LNAV_ASSISTED-v1";
    public static final String FRAME_METHOD = "AFS_IQ_FRAME_PVT-v3";

    public record Source(String sha256, IqMetadata metadata, Pvt reference) {}

    public record Result(List<Pvt> pvt, Map<String, Object> observations,
            server.pvt.DtnDelay.Evidence delayEvidence) {
        public Result(List<Pvt> pvt, Map<String, Object> observations) {
            this(pvt, observations, null);
        }
    }

    private final Path executable, nativeDirectory;
    private final Semaphore slot = new Semaphore(1);
    private volatile Process process;
    private volatile boolean closed;

    public IqReceiver(
            @Value("${lnis.iq.receiver:/app/iq/pocket_trk}") String executable,
            @Value("${lnis.native.dir:native}") String nativeDirectory) {
        this.executable = Path.of(executable).toAbsolutePath();
        this.nativeDirectory = Path.of(nativeDirectory).toAbsolutePath();
    }

    public static Source source(String input, String sha256) {
        var lines = input.lines().toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("I/Q 생성 입력 누락");
        }
        String[] header = lines.getFirst().trim().split("\\s+");
        if (header.length != 10
                || !header[0].equals("LNIS-IQ-EARTH-2")) {
            throw new IllegalArgumentException("I/Q 생성 입력 형식 오류");
        }
        Pvt ref = new Pvt();
        ref.setWeek(Integer.parseInt(header[1]));
        ref.setTowSeconds(Double.parseDouble(header[2]));
        ref.setEcefMeters(
                new double[] {
                    Double.parseDouble(header[3]),
                    Double.parseDouble(header[4]),
                    Double.parseDouble(header[5])
                });
        ref.setVelocityMetersPerSecond(
                new double[] {
                    Double.parseDouble(header[6]),
                    Double.parseDouble(header[7]),
                    Double.parseDouble(header[8])
                });
        ref.setReceiverClockBiasSeconds(Double.parseDouble(header[9]));
        ref.setPositionValid(true);
        ref.setVelocityValid(true);
        boolean embedded = header[0].equals("LNIS-IQ-EARTH-2");
        var framePrns = new HashSet<Integer>();
        var prns = new ArrayList<Integer>();
        var nav = new ArrayList<IqNavigation>();
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.trim().split("\\s+");
            if (row.length == 2 && row[0].equals("P")) {
                prns.add(Integer.parseInt(row[1]));
            } else if (row.length == 12 && row[0].equals("N")) {
                nav.add(
                        new IqNavigation(
                                Integer.parseInt(row[1]),
                                Arrays.stream(row).skip(2).map(Integer::valueOf).toList()));
            } else if (embedded && row.length == 5 && row[0].equals("F")) {
                var payload =
                        server.afs.AfsPvtFrameCodec.decode(
                                IqFrameRecovery.inputBits(row[2], 1176),
                                IqFrameRecovery.inputBits(row[3], 846),
                                IqFrameRecovery.inputBits(row[4], 846));
                if (payload.prn() != Integer.parseInt(row[1])
                        || payload.epoch() != 0
                        || payload.week() != ref.getWeek()
                        || Double.compare(payload.tow(), ref.getTowSeconds()) != 0
                        || payload.navigation() == null
                        || !framePrns.add(payload.prn())) {
                    throw new IllegalArgumentException("I/Q 생성 프레임 식별 오류");
                }
            } else {
                throw new IllegalArgumentException("I/Q 생성 입력 레코드 오류");
            }
        }
        if (embedded && !framePrns.equals(new HashSet<>(prns))) {
            throw new IllegalArgumentException("I/Q 생성 프레임 누락");
        }
        var meta =
                new IqMetadata(
                        "AFSD",
                        embedded ? FRAME_METHOD : METHOD,
                        ref.getWeek(),
                        ref.getTowSeconds(),
                        "ECEF_CONSTANT_VELOCITY",
                        List.copyOf(prns),
                        nav.stream().filter(n -> prns.contains(n.prn())).toList());
        validate(meta);
        validateReference(ref, meta);
        ref.setSatellitesUsed(prns.size());
        return new Source(sha256, meta, ref);
    }

    public static void validate(IqMetadata m) {
        if (m == null
                || !"AFSD".equals(m.signal())
                || !FRAME_METHOD.equals(m.pvtMethod())
                || !"ECEF_CONSTANT_VELOCITY".equals(m.trajectory())
                || m.week() < 0
                || m.week() > 8190
                || !Double.isFinite(m.towSeconds())
                || m.towSeconds() < 0
                || m.towSeconds() >= 604800
                || m.prns() == null
                || m.prns().size() < 4
                || m.prns().size() > 32
                || m.prns().stream().anyMatch(p -> p == null || p < 1 || p > 32)
                || new HashSet<>(m.prns()).size() != m.prns().size()
                || m.gpsLnav() == null
                || m.gpsLnav().size() > 320) {
            throw new IllegalArgumentException("I/Q PVT 메타데이터 오류");
        }
        Map<Integer, Set<Integer>> subframes = new HashMap<>();
        for (var n : m.gpsLnav()) {
            if (n == null
                    || !m.prns().contains(n.prn())
                    || n.words24() == null
                    || n.words24().size() != 10
                    || n.words24().stream().anyMatch(w -> w == null || w < 0 || w > 0xffffff)
                    || (n.words24().getFirst() >>> 16) != 0x8b) {
                throw new IllegalArgumentException("I/Q GPS LNAV 워드 오류");
            }
            int id = (n.words24().get(1) >>> 2) & 7;
            if (id < 1 || id > 5) {
                throw new IllegalArgumentException("I/Q GPS LNAV 서브프레임 오류");
            }
            subframes.computeIfAbsent(n.prn(), k -> new HashSet<>()).add(id);
        }
        if (!FRAME_METHOD.equals(m.pvtMethod())
                && m.prns().stream()
                        .anyMatch(
                                p ->
                                        !subframes
                                                .getOrDefault(p, Set.of())
                                                .containsAll(Set.of(1, 2, 3)))) {
            throw new IllegalArgumentException("I/Q PVT에 필요한 LNAV 1·2·3 누락");
        }
    }

    private static void validateReference(Pvt p, IqMetadata m) {
        if (p == null
                || !p.isPositionValid()
                || !p.isVelocityValid()
                || p.getWeek() != m.week()
                || Double.compare(p.getTowSeconds(), m.towSeconds()) != 0
                || p.getEcefMeters() == null
                || p.getEcefMeters().length != 3
                || p.getVelocityMetersPerSecond() == null
                || p.getVelocityMetersPerSecond().length != 3
                || p.getReceiverClockBiasSeconds() == null
                || !Double.isFinite(p.getReceiverClockBiasSeconds())
                || Arrays.stream(p.getEcefMeters()).anyMatch(v -> !Double.isFinite(v))
                || Arrays.stream(p.getVelocityMetersPerSecond())
                        .anyMatch(v -> !Double.isFinite(v))) {
            throw new IllegalArgumentException("I/Q 기준 PVT 오류");
        }
    }

    public Result decode(Path file, IqMetadata metadata, Consumer<String> progress)
            throws Exception {
        return decode(file, metadata, null, progress);
    }

    public Result decode(Path file, IqMetadata metadata, server.pvt.DtnDelay.Timing timing,
            Consumer<String> progress) throws Exception {
        validate(metadata);
        if (!Files.isExecutable(executable)) {
            throw new IOException("I/Q 수신 실행기를 설치하세요.");
        }
        if (!slot.tryAcquire(30, TimeUnit.SECONDS)) {
            throw new IOException("다른 I/Q 수신 처리 중입니다. 잠시 후 재시험하세요.");
        }
        Path work = null;
        try {
            if (closed) {
                throw new IOException("I/Q 수신 종료 중");
            }
            work = Files.createTempDirectory("lnis-iq-rx-");
            Path tracking = work.resolve("tracking.log");
            String prns = String.join(",", metadata.prns().stream().map(String::valueOf).toList());
            progress.accept("PocketSDR AFS 탐색·추적 시작 · PRN " + prns + " · 90초 파일");
            // Only the file and PRN list are passed. No reference position or RAWX is supplied.
            synchronized (this) {
                if (closed) {
                    throw new IOException("I/Q 수신 종료 중");
                }
                process =
                        new ProcessBuilder(
                                        executable.toString(),
                                        "-sig",
                                        "AFSD",
                                        "-prn",
                                        prns,
                                        "-f",
                                        "12",
                                        "-fmt",
                                        "INT8X2",
                                        "-IQ",
                                        "2",
                                        "-ti",
                                        "0",
                                        "-tscale",
                                        "20",
                                        "-log",
                                        tracking.toString(),
                                        file.toString())
                                .directory(work.toFile())
                                .redirectErrorStream(true)
                                .redirectOutput(work.resolve("receiver.log").toFile())
                                .start();
            }
            Process running = process;
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
            while (!running.waitFor(2, TimeUnit.SECONDS)) {
                if (closed
                        || System.nanoTime() > deadline
                        || (Files.exists(tracking) && Files.size(tracking) > 16_777_216)) {
                    throw new IOException("I/Q 추적 제한 초과 또는 중단");
                }
            }
            if (running.exitValue() != 0 || !Files.isRegularFile(tracking)) {
                try (var errors = Files.newInputStream(work.resolve("receiver.log"))) {
                    log.error(
                            "I/Q receiver exit {}: {}",
                            running.exitValue(),
                            new String(
                                    errors.readNBytes(8192),
                                    java.nio.charset.StandardCharsets.UTF_8));
                }
                throw new IOException("I/Q 추적 실행 실패: " + running.exitValue());
            }
            progress.accept("탐색·추적 종료 · CRC 통과 프레임 복원·공통 지연 PVT 계산 시작");
            var result = calculate(tracking, metadata, timing);
            String navigationSource =
                    FRAME_METHOD.equals(metadata.pvtMethod())
                            ? "AFS 프레임 복원 항법정보"
                            : "JSON 항법정보 보조 방식";
            progress.accept(
                    "지구 PVT 계산 완료 · 유효 "
                            + result.pvt().stream().filter(Pvt::isPositionValid).count()
                            + " / "
                            + result.pvt().size()
                            + " 시점 · "
                            + navigationSource);
            return result;
        } finally {
            Process running = process;
            try {
                if (running != null && running.isAlive()) {
                    running.destroyForcibly();
                    running.waitFor(10, TimeUnit.SECONDS);
                }
            } finally {
                process = null;
                slot.release();
            }
            if (work != null) { // Only this invocation's known diagnostic files; no recursive
                // deletion.
                try {
                    for (String name :
                            List.of("tracking.log", "receiver.log", ".pocket_navdata.csv")) {
                        Files.deleteIfExists(work.resolve(name));
                    }
                    Files.deleteIfExists(work);
                } catch (IOException error) {
                    log.warn("I/Q diagnostic cleanup failed: {}", work, error);
                }
            }
        }
    }

    Result calculate(Path tracking, IqMetadata metadata) throws IOException {
        return calculate(tracking, metadata, null);
    }

    Result calculate(Path tracking, IqMetadata metadata, server.pvt.DtnDelay.Timing timing) throws IOException {
        validate(metadata);
        var recovered = IqFrameRecovery.read(tracking, metadata);
        var anchor = recovered.frames().getFirst().startedAt();
        var measured = timing == null ? new server.pvt.DtnDelay.Timing(anchor, anchor) : timing;
        boolean negative = measured.seconds() < 0;
        var restored = server.afs.DelayTransferCodec.restoreDecoded(recovered.frames(), negative
                ? new server.pvt.DtnDelay.Timing(measured.startedAt(), measured.startedAt()) : measured);
        var evidence = restored.evidence();
        List<Pvt> values;
        if (negative) {
            String message = "음수 지연 · 송수신 PC 시계 동기화를 확인하세요.";
            var invalid = new Pvt();
            invalid.setWeek(evidence.originalTime().week());
            invalid.setTowSeconds(evidence.originalTime().towSeconds());
            invalid.setMessage(message);
            values = List.of(invalid);
            evidence = new server.pvt.DtnDelay.Evidence(measured, measured.seconds(),
                    server.pvt.DtnDelay.C * measured.seconds(), evidence.originalTime(), null,
                    List.of(), message);
        } else {
            try (var codec = new NativePvtCodec(nativeDirectory)) {
                values = codec.calculate(restored.records());
            }
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var view = json.<java.util.Map<String, Object>>convertValue(
                server.common.DtnObservationView.fromRecords(restored.records())
                        .withReceivedValues(restored.receivedValues()),
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
        view.put("source", "IQ_FRAME");
        view.put("assistance", "실제 I/Q 복호 프레임 · 가상 송신 시각 보정 · 공통 지연 적용");
        var trackingEpochs = new ArrayList<Object>();
        for (var entry : parse(tracking, metadata).entrySet()) {
            var signals = new ArrayList<Object>();
            for (double[] v : entry.getValue().values()) {
                signals.add(Map.of("satelliteId", (int) v[0], "pseudorangeMeters", v[1],
                        "dopplerHz", v[2], "carrierToNoiseDbHz", v[3], "carrierPhaseCycles", -v[4]));
            }
            trackingEpochs.add(Map.of("elapsedSeconds", entry.getKey(), "observations", signals));
        }
        view.put("trackingEpochs", trackingEpochs);
        return new Result(values, view, evidence);
    }

    static SortedMap<Integer, SortedMap<Integer, double[]>> parse(Path tracking, IqMetadata m)
            throws IOException {
        var epochs = new TreeMap<Integer, SortedMap<Integer, double[]>>();
        try (var reader = Files.newBufferedReader(tracking)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("$IQOBS,")) {
                    continue;
                }
                String[] row = line.split(",");
                if (row.length != 9) {
                    throw new IOException("추적 출력 형식 오류");
                }
                double t = Double.parseDouble(row[1]), coff = Double.parseDouble(row[5]);
                int week = Integer.parseInt(row[2]),
                        prn = Integer.parseInt(row[3]),
                        towMs = Integer.parseInt(row[4]);
                double dop = Double.parseDouble(row[6]),
                        phase = Double.parseDouble(row[7]),
                        cno = Double.parseDouble(row[8]);
                if (!Double.isFinite(t)
                        || t < 0
                        || t >= 90
                        || t != Math.rint(t)
                        || !m.prns().contains(prn)
                        || week < 0
                        || week > 8191
                        || towMs < 0
                        || towMs >= 604800000
                        || !Double.isFinite(coff)
                        || coff < 0
                        || coff >= .002
                        || !Double.isFinite(dop)
                        || Math.abs(dop) > 20000
                        || !Double.isFinite(phase)
                        || !Double.isFinite(cno)
                        || cno < 0
                        || cno > 100) {
                    throw new IOException("추적 관측값 범위 오류");
                }
                // ch->time is the start of a 2 ms correlation; decoded sync TOW is its end.
                double elapsed =
                        (m.week() - week) * 604800.0
                                + m.towSeconds()
                                + t
                                - towMs * .001
                                + .002
                                + coff;
                // The tracker wraps millisecond TOW before its next CRC-verified week update.
                if (elapsed > 302400) {
                    elapsed -= 604800;
                }
                if (elapsed < -302400) {
                    elapsed += 604800;
                }
                double range = elapsed * 299792458.;
                if (range < 1e7 || range > 5e7) {
                    continue;
                } // Ambiguous/invalid frame time is not a usable GPS range.
                var channel = epochs.computeIfAbsent((int) t, k -> new TreeMap<>());
                if (channel.put(prn, new double[] {prn, range, dop, cno, phase}) != null) {
                    throw new IOException("중복 추적 관측값");
                }
            }
        }
        return epochs;
    }

    /** The decoded frame retains the original one-epoch comparison reference. */
    public static List<Pvt> references(IqMetadata m, List<Pvt> reference, List<Pvt> received) {
        if (reference == null || reference.size() != 1) {
            return List.of();
        }
        Pvt initial = reference.getFirst();
        validateReference(initial, m);
        if (FRAME_METHOD.equals(m.pvtMethod())) return List.of(initial);
        return List.of();
    }

    public static Map<String, Object> comparison(List<Pvt> reference, List<Pvt> received) {
        if (reference.isEmpty() || received.isEmpty()) {
            return Map.of("verdict", "INCONCLUSIVE", "method", METHOD);
        }
        var measured = new LinkedHashMap<>(DtnComparison.compare(reference, received));
        // No undocumented RF accuracy tolerance: retain differences, not bit-exact PASS/FAIL.
        measured.remove("positionToleranceMeters");
        measured.remove("velocityToleranceMetersPerSecond");
        measured.remove("clockToleranceSeconds");
        measured.put(
                "verdict",
                received.stream().anyMatch(Pvt::isPositionValid) ? "MEASURED" : "INCONCLUSIVE");
        measured.put("method", METHOD);
        measured.put("message", "I/Q 추적 오차 측정 · 합격 허용오차 미설정");
        return measured;
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        if (process != null) {
            process.destroyForcibly();
        }
    }
}
