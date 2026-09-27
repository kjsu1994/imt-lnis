package server.gnss;

import server.common.Hashing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * LNIS canonical GRAW 메시지의 binary wire 형식을 인코딩하고 검증한다.
 *
 * <p>레코드는 LGRW magic, schema version, 메시지 종류, UUID, sequence, 수집 시각, payload 길이, payload와 CRC32를
 * 포함한다. 파일에서는 각 레코드 앞에 4 byte big-endian 길이를 추가한다. decode는 단순 역직렬화가 아니라 magic/version/길이/CRC 및 메시지별
 * payload 구조까지 검증한다.
 */
public final class GrawCodec {
    private static final byte[] MAGIC = "LGRW".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_LENGTH = 62;
    public static final int MAXIMUM_RECORD_LENGTH = 1_048_642;

    private GrawCodec() {}

    public enum MessageType {
        OBSERVATION_EPOCH(1),
        NAVIGATION_UPDATE(2),
        RECEIVER_METADATA(3);
        final int wire;

        MessageType(int wire) {
            this.wire = wire;
        }

        static MessageType fromWire(int value) {
            for (var type : values()) {
                if (type.wire == value) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown GRAW message type");
        }
    }

    public enum Constellation {
        GPS(0),
        SBAS(1),
        GALILEO(2),
        BEIDOU(3),
        IMES(4),
        QZSS(5),
        GLONASS(6),
        NAVIC(7),
        UNKNOWN(255);
        final int wire;

        Constellation(int wire) {
            this.wire = wire;
        }

        public static Constellation fromUblox(int value) {
            for (var x : values()) {
                if (x.wire == value) {
                    return x;
                }
            }
            return UNKNOWN;
        }
    }

    public sealed interface Message permits ObservationEpoch, NavigationUpdate, ReceiverMetadata {
        MessageType type();
    }

    /** 한 위성·신호에 대한 u-blox RAWX 원시 관측값이다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class Observation {
        /** 수신기와 위성 사이의 의사거리이며 단위는 meter다. */
        double pseudorangeMeters;

        /** 반송파 위상 누적값이며 단위는 cycle다. */
        double carrierPhaseCycles;

        /** 반송파 Doppler 측정값이며 단위는 Hz다. */
        float dopplerHz;

        /** GPS, Galileo 등 GNSS Constellation의 wire 식별값이다. */
        int constellationId;

        /** Constellation 내부 위성 식별 번호다. */
        int satelliteId;

        /** 위성에서 송신한 신호 종류 식별 번호다. */
        int signalId;

        /** GLONASS 등에서 사용하는 주파수 채널 식별값이다. */
        int frequencyId;

        /** 수신기가 해당 신호를 연속 추적한 시간이며 단위는 millisecond다. */
        int lockTimeMilliseconds;

        /** 반송파 대 잡음비(C/N0)이며 단위는 dB-Hz다. */
        int carrierToNoiseDbHz;

        /** u-blox 형식의 의사거리 표준편차 코드값이다. */
        int pseudorangeStdDev;

        /** u-blox 형식의 반송파 위상 표준편차 코드값이다. */
        int carrierPhaseStdDev;

        /** u-blox 형식의 Doppler 표준편차 코드값이다. */
        int dopplerStdDev;

        /** 측정 유효성·Cycle Slip 등 u-blox tracking status bit mask다. */
        int trackingStatus;
    }

    /** 동일 GNSS 측정 Epoch에 속하는 위성별 원시 관측값 묶음이다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class ObservationEpoch implements Message {
        /** GPS week 시작부터 수신기 측정 시각까지의 시간이며 단위는 second다. */
        double receiverTowSeconds;

        /** 측정 시각의 GPS week 번호다. */
        int week;

        /** 수신기가 보고한 GPS-UTC Leap Second 값이다. */
        int leapSeconds;

        /** 시각·Leap Second 유효성 등을 나타내는 수신기 상태 bit mask다. */
        int receiverStatus;

        /** 원본 UBX-RXM-RAWX payload의 메시지 버전이다. */
        int rawxVersion;

        /** 이 Epoch에 포함된 위성·신호별 관측값 목록이다. */
        List<Observation> observations;

        @Override
        public MessageType type() {
            return MessageType.OBSERVATION_EPOCH;
        }
    }

    /** 한 위성에서 수신한 UBX-SFRBX 항법 데이터 word 묶음이다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class NavigationUpdate implements Message {
        /** GNSS Constellation wire 식별값이다. */
        int constellationId;

        /** Constellation 내부 위성 식별 번호다. */
        int satelliteId;

        /** 항법 데이터를 수신한 신호 종류 식별 번호다. */
        int signalId;

        /** GLONASS 등의 주파수 채널 식별값이다. */
        int frequencyId;

        /** 원본 UBX-RXM-SFRBX payload의 메시지 버전이다. */
        int sfrbxVersion;

        /** 수신한 unsigned 32-bit 항법 데이터 word 목록이다. */
        List<Long> words;

        @Override
        public MessageType type() {
            return MessageType.NAVIGATION_UPDATE;
        }
    }

    /** GNSS 수집 환경을 결과와 함께 추적하기 위한 수신기 메타데이터다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class ReceiverMetadata implements Message {
        /** 사용자가 입력한 GNSS 수신기 모델명이다. */
        String receiverModel;

        /** 수신기에 설치된 펌웨어 버전 문자열이다. */
        String firmwareVersion;

        /** 데이터를 수집한 Windows COM 포트 이름이다. */
        String portName;

        /** 수집 당시 직렬 포트 속도이며 단위는 baud다. */
        int baudRate;

        /** 사용자가 지정한 수집 세션 표시 이름이다. */
        String sessionName;

        @Override
        public MessageType type() {
            return MessageType.RECEIVER_METADATA;
        }
    }

    /** 모든 canonical GRAW 메시지에 공통 식별자와 수집 시각을 부여하는 envelope다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class Envelope {
        /** 같은 GNSS 수집에서 생성된 레코드를 묶는 UUID다. */
        UUID testId;

        /** 개별 GRAW 레코드의 중복 없는 UUID다. */
        UUID messageId;

        /** 수집 안에서 0부터 증가하는 레코드 순번이다. */
        long sequence;

        /** Agent가 해당 GNSS 메시지를 수집한 UTC 시각이다. */
        Instant capturedAt;

        /** Observation, Navigation 또는 Receiver Metadata 본문이다. */
        Message message;
    }

    /** canonical GRAW 레코드 한 개를 binary wire representation으로 직렬화한다. */
    public static byte[] encode(Envelope envelope) {
        byte[] payload = encodePayload(envelope.message);
        if (payload.length > 1024 * 1024) {
            throw new IllegalArgumentException("GRAW payload too large");
        }
        ByteBuffer out =
                ByteBuffer.allocate(HEADER_LENGTH + payload.length + 4).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC)
                .putShort((short) 1)
                .put((byte) envelope.message.type().wire)
                .put((byte) 0)
                .putShort((short) HEADER_LENGTH)
                .putInt(payload.length);
        putGuid(out, envelope.testId);
        putGuid(out, envelope.messageId);
        long micros =
                Math.addExact(
                        Math.multiplyExact(envelope.capturedAt.getEpochSecond(), 1_000_000),
                        envelope.capturedAt.getNano() / 1_000);
        out.putLong(envelope.sequence).putLong(micros).put(payload);
        out.putInt((int) Hashing.crc32(out.array(), 0, out.position()));
        return out.array();
    }

    /** 레코드 header와 CRC를 검증하고 메시지 종류에 맞는 payload record로 복원한다. */
    public static Envelope decode(byte[] record) {
        if (record.length < HEADER_LENGTH + 4 || !Arrays.equals(MAGIC, Arrays.copyOf(record, 4))) {
            throw new IllegalArgumentException("Invalid GRAW magic");
        }
        ByteBuffer in = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
        in.position(4);
        int version = Short.toUnsignedInt(in.getShort());
        MessageType type = MessageType.fromWire(Byte.toUnsignedInt(in.get()));
        int reserved = Byte.toUnsignedInt(in.get());
        int header = Short.toUnsignedInt(in.getShort());
        int length = in.getInt();
        if (version != 1
                || reserved != 0
                || header != HEADER_LENGTH
                || length < 0
                || length > 1024 * 1024
                || record.length != HEADER_LENGTH + length + 4) {
            throw new IllegalArgumentException("Unsupported GRAW header");
        }
        UUID testId = getGuid(in);
        UUID messageId = getGuid(in);
        long sequence = in.getLong();
        long micros = in.getLong();
        long expected =
                Integer.toUnsignedLong(
                        ByteBuffer.wrap(record, record.length - 4, 4)
                                .order(ByteOrder.BIG_ENDIAN)
                                .getInt());
        if (Hashing.crc32(record, 0, record.length - 4) != expected) {
            throw new IllegalArgumentException("GRAW CRC32 mismatch");
        }
        byte[] payload = new byte[length];
        in.get(payload);
        Instant capturedAt =
                Instant.ofEpochSecond(
                        Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000);
        return new Envelope(testId, messageId, sequence, capturedAt, decodePayload(type, payload));
    }

    private static void putGuid(ByteBuffer out, UUID uuid) {
        out.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
    }

    private static UUID getGuid(ByteBuffer in) {
        return new UUID(in.getLong(), in.getLong());
    }

    /**
     * 4 byte 길이 prefix가 붙은 전체 GRAW 파일을 레코드 배열로 분리한다.
     *
     * <p>잘린 마지막 레코드, 허용 범위를 벗어난 길이 또는 내부 CRC 오류가 있으면 즉시 예외를 발생시켜 손상된 입력이 AFS 송신 단계로 넘어가지 않게 한다.
     */
    public static List<byte[]> splitLengthPrefixed(byte[] data) {
        List<byte[]> records = new ArrayList<>();
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (in.hasRemaining()) {
            if (in.remaining() < 4) {
                throw new IllegalArgumentException("Truncated GRAW length");
            }
            int length = in.getInt();
            if (length <= 0 || length > MAXIMUM_RECORD_LENGTH || in.remaining() < length) {
                throw new IllegalArgumentException("Invalid GRAW record length");
            }
            byte[] record = new byte[length];
            in.get(record);
            decode(record);
            records.add(record);
        }
        return records;
    }

    private static byte[] encodePayload(Message message) {
        ByteBuffer out = ByteBuffer.allocate(estimate(message)).order(ByteOrder.BIG_ENDIAN);
        switch (message) {
            case ObservationEpoch x -> {
                out.putDouble(x.receiverTowSeconds)
                        .putShort((short) x.week)
                        .put((byte) x.leapSeconds)
                        .put((byte) x.receiverStatus)
                        .put((byte) x.rawxVersion)
                        .putShort((short) x.observations.size());
                for (Observation o : x.observations) {
                    out.put((byte) o.constellationId)
                            .put((byte) o.satelliteId)
                            .put((byte) o.signalId)
                            .put((byte) o.frequencyId)
                            .putDouble(o.pseudorangeMeters)
                            .putDouble(o.carrierPhaseCycles)
                            .putFloat(o.dopplerHz)
                            .putShort((short) o.lockTimeMilliseconds)
                            .put((byte) o.carrierToNoiseDbHz)
                            .put((byte) o.pseudorangeStdDev)
                            .put((byte) o.carrierPhaseStdDev)
                            .put((byte) o.dopplerStdDev)
                            .put((byte) o.trackingStatus);
                }
            }
            case NavigationUpdate x -> {
                out.put((byte) x.constellationId)
                        .put((byte) x.satelliteId)
                        .put((byte) x.signalId)
                        .put((byte) x.frequencyId)
                        .put((byte) x.sfrbxVersion)
                        .putShort((short) x.words.size());
                x.words.forEach(word -> out.putInt((int) (word & 0xffff_ffffL)));
            }
            case ReceiverMetadata metadata -> {
                putText(out, metadata.receiverModel);
                putText(out, metadata.firmwareVersion);
                putText(out, metadata.portName);
                out.putInt(metadata.baudRate);
                putText(out, metadata.sessionName);
            }
        }
        return Arrays.copyOf(out.array(), out.position());
    }

    private static Message decodePayload(MessageType type, byte[] payload) {
        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        Message result;
        if (type == MessageType.OBSERVATION_EPOCH) {
            double tow = in.getDouble();
            int week = Short.toUnsignedInt(in.getShort());
            int leap = in.get();
            int status = Byte.toUnsignedInt(in.get());
            int version = Byte.toUnsignedInt(in.get());
            int count = Short.toUnsignedInt(in.getShort());
            List<Observation> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int gnss = Byte.toUnsignedInt(in.get());
                int sv = Byte.toUnsignedInt(in.get());
                int sig = Byte.toUnsignedInt(in.get());
                int freq = Byte.toUnsignedInt(in.get());
                list.add(
                        new Observation(
                                in.getDouble(),
                                in.getDouble(),
                                in.getFloat(),
                                gnss,
                                sv,
                                sig,
                                freq,
                                Short.toUnsignedInt(in.getShort()),
                                Byte.toUnsignedInt(in.get()),
                                Byte.toUnsignedInt(in.get()),
                                Byte.toUnsignedInt(in.get()),
                                Byte.toUnsignedInt(in.get()),
                                Byte.toUnsignedInt(in.get())));
            }
            result = new ObservationEpoch(tow, week, leap, status, version, list);
        } else if (type == MessageType.NAVIGATION_UPDATE) {
            int gnss = Byte.toUnsignedInt(in.get());
            int sv = Byte.toUnsignedInt(in.get());
            int sig = Byte.toUnsignedInt(in.get());
            int freq = Byte.toUnsignedInt(in.get());
            int version = Byte.toUnsignedInt(in.get());
            int count = Short.toUnsignedInt(in.getShort());
            List<Long> words = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                words.add(Integer.toUnsignedLong(in.getInt()));
            }
            result = new NavigationUpdate(gnss, sv, sig, freq, version, words);
        } else {
            result =
                    new ReceiverMetadata(
                            getText(in), getText(in), getText(in), in.getInt(), getText(in));
        }
        if (in.hasRemaining()) {
            throw new IllegalArgumentException("Trailing GRAW payload bytes");
        }
        return result;
    }

    private static int estimate(Message message) {
        if (message instanceof ObservationEpoch x) {
            return 15 + x.observations.size() * 31;
        }
        if (message instanceof NavigationUpdate x) {
            return 7 + x.words.size() * 4;
        }
        ReceiverMetadata x = (ReceiverMetadata) message;
        return 4
                + 2 * 4
                + utf8(x.receiverModel).length
                + utf8(x.firmwareVersion).length
                + utf8(x.portName).length
                + utf8(x.sessionName).length;
    }

    private static byte[] utf8(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    private static void putText(ByteBuffer out, String value) {
        byte[] bytes = utf8(value);
        out.putShort((short) bytes.length).put(bytes);
    }

    private static String getText(ByteBuffer in) {
        int length = Short.toUnsignedInt(in.getShort());
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
