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
    public static final String FRAME_METHOD = "AFS_IQ_FRAME_PVT-v1";
    public static final String RF_METHOD = "AFS_IQ_RF_DELAY-v1";

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String sha256, IqMetadata metadata, Pvt reference, String grawBase64) {
        public Source(String sha256, IqMetadata metadata, Pvt reference) {
            this(sha256, metadata, reference, null);
        }
    }

    public record Result(List<Pvt> pvt, Map<String, Object> observations,
            server.pvt.DtnDelay.Evidence delayEvidence, List<Pvt> rfBaselinePvt) {
        public Result(List<Pvt> pvt, Map<String, Object> observations,
                server.pvt.DtnDelay.Evidence delayEvidence) {
            this(pvt, observations, delayEvidence, List.of());
        }
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
        var frames = new HashMap<Integer, server.afs.AfsPvtFrameCodec.Payload>();
        var anchors = new HashMap<Integer, double[]>();
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
            } else if (row.length == 4 && row[0].equals("O")) {
                int prn = Integer.parseInt(row[1]);
                double range = Double.parseDouble(row[2]), doppler = Double.parseDouble(row[3]);
                if (!Double.isFinite(range) || range < 1e7 || range > 5e7
                        || !Double.isFinite(doppler) || Math.abs(doppler) > 20000
                        || anchors.putIfAbsent(prn, new double[] {range, doppler}) != null) {
                    throw new IllegalArgumentException("I/Q 파형 기준 관측값 오류");
                }
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
                frames.put(payload.prn(), payload);
            } else {
                throw new IllegalArgumentException("I/Q 생성 입력 레코드 오류");
            }
        }
        if (embedded && !framePrns.equals(new HashSet<>(prns))) {
            throw new IllegalArgumentException("I/Q 생성 프레임 누락");
        }
        if (!anchors.isEmpty()) {
            if (!anchors.keySet().equals(framePrns)) {
                throw new IllegalArgumentException("I/Q 파형 기준 위성 누락");
            }
            anchors.forEach((prn, values) -> {
                var frame = frames.get(prn);
                if (Math.abs(values[0] - frame.range()) > 1e-6
                        || Double.compare(values[1], frame.doppler()) != 0) {
                    throw new IllegalArgumentException("I/Q 파형 기준과 SB4 관측값 불일치");
                }
            });
        }
        var meta =
                new IqMetadata(
                        "AFSD",
                        anchors.isEmpty() ? FRAME_METHOD : RF_METHOD,
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
                || !(FRAME_METHOD.equals(m.pvtMethod()) || RF_METHOD.equals(m.pvtMethod()))
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
            progress.accept("탐색·추적 종료 · CRC 통과 항법정보 복원·PVT 계산 시작");
            var result = calculate(tracking, metadata, timing);
            String navigationSource = "AFS 프레임 복원 항법정보";
            progress.accept(
                    (RF_METHOD.equals(metadata.pvtMethod()) ? "RF 기준·지연 반영 PVT 계산 완료 · 유효 " : "지구 PVT 계산 완료 · 유효 ")
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
        if (RF_METHOD.equals(metadata.pvtMethod())) {
            return calculateRf(tracking, metadata, timing, recovered);
        }
        var anchor = recovered.frames().getFirst().startedAt();
        var measured = timing == null ? new server.pvt.DtnDelay.Timing(anchor, anchor) : timing;
        boolean negative = measured.seconds() < 0;
        var restored = server.afs.DelayTransferCodec.restoreDecoded(recovered.frames(), negative
                ? new server.pvt.DtnDelay.Timing(measured.startedAt(), measured.startedAt()) : measured);
        var evidence = restored.evidence();
        List<Pvt> values;
        if (negative) {
            String message = "음수 지연 · 송수신 시각 차이와 TCP 보정 상태를 확인하세요.";
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

    private Result calculateRf(Path tracking, IqMetadata metadata, server.pvt.DtnDelay.Timing timing,
            IqFrameRecovery.Recovered recovered) throws IOException {
        var epochs = parse(tracking, metadata);
        var frameRecords = server.afs.AfsPvtFrameCodec.records(recovered.frames().stream()
                .map(server.afs.AfsPvtFrameCodec.DelayPayload::observation).toList());
        var measured = timing == null ? new server.pvt.DtnDelay.Timing(
                recovered.frames().getFirst().startedAt(), recovered.frames().getFirst().startedAt()) : timing;
        List<byte[]> selected = null;
        List<double[]> selectedSignals = List.of();
        Pvt baseline = null;
        Integer elapsed = null;
        try (var codec = new NativePvtCodec(nativeDirectory)) {
            for (var navigation : recovered.navigation()) {
                codec.navigation(navigation.prn(), metadata.week(),
                        navigation.words24().stream().mapToInt(Integer::intValue).toArray());
            }
            for (var entry : epochs.entrySet()) {
                var time = server.pvt.DtnDelay.shift(metadata.week(), metadata.towSeconds(), entry.getKey());
                var signals = entry.getValue().values().stream()
                        .filter(v -> recovered.prns().contains((int) v[0])).toList();
                var records = rfRecords(frameRecords, time, signals);
                var value = signals.isEmpty() ? codec.calculate(records).getFirst()
                        : solveRf(codec, time, signals, 0);
                if (selected == null || value.isPositionValid()) {
                    selected = records;
                    selectedSignals = signals;
                    baseline = value;
                    elapsed = entry.getKey();
                }
                if (value.isPositionValid()) break;
            }
            if (selected == null) {
                selected = rfRecords(frameRecords,
                        new server.pvt.DtnDelay.Time(metadata.week(), metadata.towSeconds()), List.of());
                baseline = codec.calculate(selected).getFirst();
                baseline.setMessage("RF 추적 관측값 없음 · SB4 관측값으로 대체하지 않습니다.");
            }
            var converted = server.pvt.DtnDelay.convert(selected, measured);
            List<Pvt> values;
            if (converted.evidence().error() == null) {
                values = selectedSignals.isEmpty() ? codec.calculate(converted.records())
                        : List.of(solveRf(codec, converted.evidence().shiftedTime(), selectedSignals,
                                converted.evidence().addedMeters()));
            } else {
                var invalid = new Pvt();
                invalid.setWeek(baseline.getWeek());
                invalid.setTowSeconds(baseline.getTowSeconds());
                invalid.setMessage(converted.evidence().error());
                values = List.of(invalid);
            }
            var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var view = json.<Map<String, Object>>convertValue(
                    server.common.DtnObservationView.fromRecords(converted.records()),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var original = (server.gnss.GrawCodec.ObservationEpoch) server.gnss.GrawCodec.decode(
                    selected.getLast()).message();
            var wire = json.createObjectNode();
            var observations = wire.putArray("observations");
            for (var o : original.observations()) {
                var row = observations.addObject();
                row.setAll((com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(o));
                var signal = epochs.get(elapsed).get(o.satelliteId());
                row.put("dopplerHz", signal[2]);
                row.put("carrierToNoiseDbHz", signal[3]);
                row.put("week", original.week());
                row.put("towSeconds", original.receiverTowSeconds());
                if (measured.seconds() >= 0) {
                    row.set("alignedTransmitAt", json.valueToTree(
                            server.pvt.DelayTime.transmit(measured.startedAt(), o.pseudorangeMeters())));
                }
            }
            wire.put("week", original.week());
            wire.put("towSeconds", original.receiverTowSeconds());
            view.put("source", "IQ_RF");
            view.put("receivedValues", wire);
            view.put("selectedElapsedSeconds", elapsed);
            view.put("assistance", "RF 추적 관측값 · 항법정보는 CRC 통과 AFS 프레임에서 복원");
            view.put("trackingEpochs", epochs.entrySet().stream().map(entry -> Map.of(
                    "elapsedSeconds", entry.getKey(), "observations", entry.getValue().values().stream()
                            .map(v -> Map.of("satelliteId", (int) v[0], "pseudorangeMeters", v[1],
                                    "dopplerHz", v[2], "carrierToNoiseDbHz", v[3],
                                    "carrierPhaseCycles", -v[4])).toList())).toList());
            return new Result(values, view, converted.evidence(), List.of(baseline));
        }
    }

    private static Pvt solveRf(NativePvtCodec codec, server.pvt.DtnDelay.Time time,
            List<double[]> signals, double addedMeters) {
        double[] input = new double[signals.size() * 4];
        for (int i = 0; i < signals.size(); i++) {
            var signal = signals.get(i);
            input[i * 4] = signal[0];
            input[i * 4 + 1] = signal[1] + addedMeters;
            input[i * 4 + 2] = signal[2];
            input[i * 4 + 3] = signal[3];
        }
        return codec.solve(time.week(), time.towSeconds(), input);
    }

    private static List<byte[]> rfRecords(List<byte[]> frames, server.pvt.DtnDelay.Time time,
            List<double[]> signals) {
        var records = new ArrayList<byte[]>();
        for (byte[] record : frames) {
            if (server.gnss.GrawCodec.decode(record).message() instanceof server.gnss.GrawCodec.NavigationUpdate) {
                records.add(record);
            }
        }
        var observations = signals.stream().map(v -> new server.gnss.GrawCodec.Observation(
                v[1], -v[4], (float) v[2], 0, (int) v[0], 0, 0, 0,
                (int) Math.round(v[3]), 0, 0, 0, 1)).toList();
        var epoch = new server.gnss.GrawCodec.ObservationEpoch(
                time.towSeconds(), time.week(), 0, 0, 1, observations);
        records.add(server.gnss.GrawCodec.encode(new server.gnss.GrawCodec.Envelope(
                new UUID(0, 0), new UUID(0, records.size() + 1L), records.size(), java.time.Instant.EPOCH, epoch)));
        return records;
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
        if (FRAME_METHOD.equals(m.pvtMethod()) || RF_METHOD.equals(m.pvtMethod())) return List.of(initial);
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
