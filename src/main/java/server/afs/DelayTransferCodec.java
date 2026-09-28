package server.afs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import server.common.DtnModels.*;
import server.gnss.GrawCodec;
import server.pvt.DelayTime;
import server.pvt.DtnDelay;

import java.time.Instant;
import java.util.*;

/** 원본·Reference 없이 송신 시각으로 계산하는 RAW/AFS 공통 전달 규격. */
public final class DelayTransferCodec {
    public static final String AFS = "LNIS-AFS-GNSS-v5";
    public static final String RAW = "LNIS-GRAW-DELAY-v2";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private DelayTransferCodec() {}

    public static boolean supports(Transfer value) {
        return value != null && (AFS.equals(value.getFormat()) || RAW.equals(value.getFormat()));
    }

    public record Restored(List<byte[]> records, DtnDelay.Evidence evidence, JsonNode receivedValues) {}

    public static Transfer prepare(UUID id, List<byte[]> records, boolean raw,
                                   Instant start, NativeAfsCodec codec) {
        Objects.requireNonNull(start, "시험 시작 시각");
        Transfer transfer = new Transfer();
        transfer.setTestId(id);
        transfer.setTestType(raw ? "GNSS_RAW" : "AFS_METADATA");
        transfer.setSchemaVersion(raw ? 2 : 5);
        transfer.setFormat(raw ? RAW : AFS);
        if (raw) {
            transfer.setRaw(rawValues(records, start));
            return transfer;
        }
        var payloads = AfsPvtFrameCodec.select(records);
        if (payloads.stream().noneMatch(v -> v.navigation() != null)) {
            throw new IllegalArgumentException("AFS 시험에는 GPS LNAV 항법정보가 필요합니다.");
        }
        Map<Integer, List<Frame>> grouped = new LinkedHashMap<>();
        int index = 0;
        for (var value : payloads) {
            var blocks = AfsPvtFrameCodec.encodeDelay(value, start);
            Frame frame = new Frame();
            frame.setIndex(index++);
            frame.setPrn(value.prn());
            frame.setWeek(value.week());
            frame.setAfsItow((int) (value.tow() / 1200));
            frame.setToi((int) (value.tow() % 1200 / 12));
            frame.setFrameBase64(Base64.getEncoder().encodeToString(
                    codec.encode(frame.getToi(), blocks.sb2(), blocks.sb3(), blocks.sb4())));
            grouped.computeIfAbsent(value.prn(), key -> new ArrayList<>()).add(frame);
        }
        transfer.setSatellites(grouped.entrySet().stream()
                .map(e -> new AfsSatellite(0, e.getKey(), e.getValue(), null)).toList());
        return transfer;
    }

    public static JsonNode rawValues(List<byte[]> records, Instant start) {
        ObjectNode root = JSON.createObjectNode();
        root.put("startedAt", start.toString());
        var array = root.putArray("records");
        for (byte[] bytes : records) {
            ObjectNode record = JSON.valueToTree(AfsMetadataCodec.record(GrawCodec.decode(bytes)));
            for (JsonNode item : record.path("observation").path("observations")) {
                ObjectNode observation = (ObjectNode) item;
                double range = observation.path("pseudorangeMeters").asDouble(Double.NaN);
                observation.remove("pseudorangeMeters");
                observation.set("transmitAt", Double.isFinite(range) && range > 0
                        ? JSON.valueToTree(DelayTime.transmit(start, range)) : JSON.nullNode());
            }
            array.add(record);
        }
        return root;
    }

    /** 원본 저장 필드는 새 전송 JSON에 직렬화하지 않는다. */
    public static ObjectNode packet(ObjectMapper mapper, Transfer transfer) {
        ObjectNode packet = mapper.valueToTree(transfer);
        if (supports(transfer)) {
            if (packet.path("satellites").isArray()) {
                for (JsonNode sat : packet.path("satellites")) ((ObjectNode) sat).remove("metadata");
            }
            if (AFS.equals(transfer.getFormat())) packet.remove("raw");
            else packet.remove("satellites");
            packet.remove(List.of("metadata", "referencePvt", "sourceSha256",
                    "recordCount", "prn", "frames", "grawBase64", "file"));
        }
        return packet;
    }

    public static Restored restore(Transfer transfer, NativeAfsCodec codec, DtnDelay.Timing timing) {
        if (timing == null || timing.seconds() < 0) {
            throw new IllegalArgumentException("지연 시각 누락 또는 음수 지연: PC 시계 동기화를 확인하세요.");
        }
        if (!server.common.DtnModels.PROFILE.equals(transfer.getProfile())
                || transfer.getMetadata() != null || transfer.getReferencePvt() != null
                || transfer.getGrawBase64() != null || transfer.getFile() != null) {
            throw new IllegalArgumentException("지연 전송에 원본/Reference를 포함할 수 없습니다.");
        }
        try {
            if (RAW.equals(transfer.getFormat()) && transfer.getSchemaVersion() == 2
                    && "GNSS_RAW".equals(transfer.getTestType()) && transfer.getSatellites() == null) {
                return restoreRaw(transfer.getRaw(), timing);
            }
            if (!AFS.equals(transfer.getFormat()) || transfer.getSchemaVersion() != 5
                    || !"AFS_METADATA".equals(transfer.getTestType()) || transfer.getRaw() != null
                    || transfer.getSatellites() == null || transfer.getFrames() != null) {
                throw new IllegalArgumentException("지연 전송 형식 오류");
            }
            return restoreAfs(transfer, codec, timing);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("변환 관측 JSON 오류", error);
        }
    }

    private static Restored restoreRaw(JsonNode wire, DtnDelay.Timing timing) throws java.io.IOException {
        if (wire == null || !timing.startedAt().toString().equals(wire.path("startedAt").asText())
                || !wire.path("records").isArray() || wire.path("records").size() > 15000) {
            throw new IllegalArgumentException("RAW 시각/레코드 오류");
        }
        List<byte[]> records = new ArrayList<>();
        List<DtnDelay.Satellite> satellites = new ArrayList<>();
        DtnDelay.Time original = null;
        for (JsonNode value : wire.path("records")) {
            ObjectNode record = value.deepCopy();
            JsonNode epoch = record.get("observation");
            if (epoch != null && !epoch.isNull()) {
                if (original != null) throw new IllegalArgumentException("1 Epoch만 허용합니다.");
                original = new DtnDelay.Time(epoch.path("week").asInt(),
                        epoch.path("receiverTowSeconds").asDouble());
                for (JsonNode valueObservation : epoch.path("observations")) {
                    ObjectNode observation = (ObjectNode) valueObservation;
                    if (observation.has("pseudorangeMeters")) {
                        throw new IllegalArgumentException("원본 의사거리 필드 금지");
                    }
                    JsonNode stamp = observation.remove("transmitAt");
                    Double range = null;
                    if (stamp != null && !stamp.isNull()) {
                        DelayTime transmit = JSON.treeToValue(stamp, DelayTime.class);
                        if (transmit.until(timing.startedAt()) <= 0) {
                            throw new IllegalArgumentException("가상 송신 시각 오류");
                        }
                        range = transmit.rangeAt(timing.receivedAt());
                    }
                    int status = observation.path("trackingStatus").asInt();
                    if (range == null) observation.put("trackingStatus", status & ~1);
                    observation.put("pseudorangeMeters", range == null ? 0 : range);
                    satellites.add(satellite(observation, range));
                }
                DtnDelay.Time shifted = DtnDelay.shift(original.week(), original.towSeconds(), timing.seconds());
                ((ObjectNode) epoch).put("week", shifted.week());
                ((ObjectNode) epoch).put("receiverTowSeconds", shifted.towSeconds());
            }
            records.add(GrawCodec.encode(AfsMetadataCodec.envelope(
                    JSON.treeToValue(record, AfsRecord.class))));
        }
        if (original == null) throw new IllegalArgumentException("관측 Epoch 누락");
        return new Restored(records, evidence(timing, original, satellites), wire);
    }

    private static Restored restoreAfs(Transfer transfer, NativeAfsCodec codec, DtnDelay.Timing timing) {
        List<Frame> frames = new ArrayList<>();
        Set<Integer> prns = new HashSet<>();
        for (var sat : transfer.getSatellites()) {
            if (sat.constellationId() != 0 || sat.metadata() != null || !prns.add(sat.prn())
                    || sat.frames() == null || sat.frames().isEmpty()) {
                throw new IllegalArgumentException("위성 그룹 오류");
            }
            for (var frame : sat.frames()) {
                if (!Objects.equals(frame.getPrn(), sat.prn())) {
                    throw new IllegalArgumentException("위성/프레임 식별 오류");
                }
                frames.add(frame);
            }
        }
        if (frames.isEmpty() || frames.size() > 32) throw new IllegalArgumentException("프레임 개수 오류");
        frames.sort(Comparator.comparingInt(Frame::getIndex));
        List<AfsPvtFrameCodec.Payload> payloads = new ArrayList<>();
        ObjectNode wire = JSON.createObjectNode();
        wire.put("startedAt", timing.startedAt().toString());
        var observations = wire.putArray("observations");
        List<DtnDelay.Satellite> satellites = new ArrayList<>();
        DtnDelay.Time original = null;
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame.getIndex() != i || frame.getFrameBase64() == null
                    || frame.getFrameBase64().length() != 1000 || frame.getNavigationRecordIndices() != null) {
                throw new IllegalArgumentException("프레임 순서/크기 오류");
            }
            var decoded = codec.decode(frame.getToi(), Base64.getDecoder().decode(frame.getFrameBase64()));
            if (!decoded.sb2Valid() || !decoded.sb3Valid() || !decoded.sb4Valid()) {
                throw new IllegalArgumentException("AFS CRC 오류");
            }
            var restored = AfsPvtFrameCodec.decodeDelay(decoded.sb2(), decoded.sb3(), decoded.sb4(), timing.receivedAt());
            var v = restored.observation();
            DtnDelay.Time epoch = new DtnDelay.Time(v.week(), v.tow());
            if (!restored.startedAt().equals(timing.startedAt()) || v.epoch() != 0
                    || (original != null && !original.equals(epoch))
                    || !Objects.equals(frame.getPrn(), v.prn()) || frame.getWeek() != v.week()
                    || frame.getAfsItow() != (int) (v.tow() / 1200)
                    || frame.getToi() != (int) (v.tow() % 1200 / 12)) {
                throw new IllegalArgumentException("프레임 시각/시험 조건 불일치");
            }
            original = epoch;
            ObjectNode observation = observations.addObject();
            observation.put("constellationId", 0);
            observation.put("satelliteId", v.prn());
            observation.put("signalId", 0);
            observation.put("week", v.week());
            observation.put("towSeconds", v.tow());
            observation.put("dopplerHz", v.doppler());
            observation.put("carrierToNoiseDbHz", v.cn0());
            observation.put("trackingStatus", v.status());
            observation.set("transmitAt", JSON.valueToTree(restored.transmitAt()));
            if (v.prn() != 0) satellites.add(satellite(observation, v.range()));
            var shifted = DtnDelay.shift(v.week(), v.tow(), timing.seconds());
            payloads.add(new AfsPvtFrameCodec.Payload(v.epoch(), v.measurement(), shifted.week(),
                    shifted.towSeconds(), v.prn(), v.range(), v.doppler(), v.cn0(), v.status(),
                    v.navigation(), v.ionPrn(), v.ionosphere()));
        }
        return new Restored(AfsPvtFrameCodec.records(payloads), evidence(timing, original, satellites), wire);
    }

    private static DtnDelay.Satellite satellite(JsonNode o, Double range) {
        int gnss = o.path("constellationId").asInt(), signal = o.path("signalId").asInt();
        return new DtnDelay.Satellite(gnss, o.path("satelliteId").asInt(), signal,
                null, null, null, range, (float) o.path("dopplerHz").asDouble(),
                o.path("carrierToNoiseDbHz").asInt(),
                range != null && gnss == 0 && signal == 0 && (o.path("trackingStatus").asInt() & 1) != 0);
    }

    private static DtnDelay.Evidence evidence(DtnDelay.Timing timing, DtnDelay.Time original,
                                              List<DtnDelay.Satellite> satellites) {
        return new DtnDelay.Evidence(timing, timing.seconds(), DtnDelay.C * timing.seconds(),
                original, DtnDelay.shift(original.week(), original.towSeconds(), timing.seconds()),
                List.copyOf(satellites), null);
    }
}
