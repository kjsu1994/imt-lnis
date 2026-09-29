package server.gnss;



import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Windows COM 포트에서 GNSS 데이터를 읽어 canonical GRAW 청크로 변환한다.
 *
 * <p>전용 platform thread에서 serial byte를 읽고 선택한 protocol parser에 전달한다. raw serial과 canonical GRAW를 함께
 * 계수하지만 서버에는 시험에 사용할 canonical 레코드만 별도 필드로 전달한다. stop 또는 close 시 포트를 닫고 가능한 경우 u-blox 임시 설정을 원래 값으로
 * 복원한다.
 */
public final class SerialCaptureService implements AutoCloseable {
    /** 서버의 수집 명령에서 전달받아 실제 Windows 직렬 포트에 적용하는 설정이다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class Settings {
        /** 열어야 할 Windows 직렬 포트 이름이다. */
        String portName;

        /** GNSS 장비와 통신할 전송 속도이며 단위는 baud다. */
        @lombok.Builder.Default
        int baudRate = 38400;

        /** {@code ubx}, {@code raw-only}, {@code lnis-canonical-v1} 중 수집 해석 방식이다. */
        String protocolId;

        /** GRAW 수신기 메타데이터에 기록할 사용자 지정 수집 이름이다. */
        String sessionName;

        /** GRAW 메타데이터에 기록할 수신기 모델명이다. */
        String receiverModel;

        /** GRAW 메타데이터에 기록할 수신기 펌웨어 버전이다. */
        String firmwareVersion;

        /** 포트를 연 뒤 DTR 제어선을 활성화할지 여부다. */
        boolean dtrEnabled;

        /** 포트를 연 뒤 RTS 제어선을 활성화할지 여부다. */
        boolean rtsEnabled;

        boolean singleEpoch;
    }

    /** 메모리 상한을 위해 수집 데이터를 약 1 MiB 단위로 서버에 전달하는 청크다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class CaptureChunk {
        /** 0부터 시작하며 서버가 Redis 청크 키 순서를 결정하는 번호다. */
        long index;

        /** COM 포트에서 그대로 읽은 원시 직렬 바이트다. */
        byte[] rawSerial;

        /** 시험 입력으로 사용할 길이-prefix canonical GRAW 바이트다. */
        byte[] canonical;

        /** 수집 시작 후 COM 포트에서 읽은 원시 바이트의 누적 합계다. */
        long bytesRead;

        /** canonical GRAW로 변환해 생성한 레코드 누적 합계다. */
        long records;
    }

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile SerialConnection port;
    private final WindowsSerialClient bridge = WindowsSerialClient.configured();
    private volatile Thread worker;
    private UbloxCaptureConfiguration configuration;
    private String detectedModel = "", detectedFirmware = "";
    private Consumer<byte[]> rawObserver = bytes -> {};
    private final GnssConnection connection;

    /** 독립 CLI 수집은 기존 포트 수명주기를 유지한다. 웹 노드는 상시 연결을 전달한다. */
    public SerialCaptureService() {
        this(null);
    }

    public SerialCaptureService(GnssConnection connection) {
        this.connection = connection;
    }

    public record CaptureProgress(String stage, String message, Map<String, Object> counters) {}
    public record DetectedPort(String name, String description, String identity) {
        public DetectedPort(String name, String description) {
            this(name, description, "");
        }
    }

    /** Enumerate on the OS running this node; enumeration does not open or claim a port. */
    public List<DetectedPort> ports() {
        return bridge == null ? SerialConnection.localPorts() : bridge.ports();
    }

    public List<String> portNames() {
        return ports().stream().map(DetectedPort::name).toList();
    }
    public synchronized void start(
            Settings settings, Consumer<CaptureChunk> chunks, Consumer<Throwable> failure) {
        start(settings, chunks, failure, null, () -> {});
    }

    public synchronized void start(
            Settings settings,
            Consumer<CaptureChunk> chunks,
            Consumer<Throwable> failure,
            SingleEpochCapture selection,
            Runnable completed) {
        start(settings, chunks, failure, selection, completed, progress -> {}, bytes -> {});
    }

    public synchronized void start(
            Settings settings, Consumer<CaptureChunk> chunks, Consumer<Throwable> failure,
            SingleEpochCapture selection, Runnable completed,
            Consumer<CaptureProgress> progress, Consumer<byte[]> rawObserver) {
        if (settings.singleEpoch
                && (selection == null || !"ubx".equalsIgnoreCase(settings.protocolId))) {
            throw new IllegalArgumentException("한 시점 수집은 UBX만 지원합니다.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Capture is already running");
        }
        this.rawObserver = Objects.requireNonNull(rawObserver);
        configuration = null;
        // 포트를 완전히 구성한 뒤 worker를 시작해 reader가 반쯤 적용된 직렬 설정을 보지 않게 한다.
        try {
            port = connection != null ? connection.acquire(settings)
                    : bridge == null ? SerialConnection.local(settings) : bridge.open(settings);
            if ("ubx".equalsIgnoreCase(settings.protocolId)) {
                progress.accept(new CaptureProgress("Configuring", "현재 포트의 RAWX/SFRBX 출력 설정 확인 중", Map.of()));
                detectedModel = "";
                detectedFirmware = "";
                for (var reply : exchange(UbloxParser.command(0x0a, 0x04, new byte[0]))) {
                    if (reply.messageClass() != 0x0a || reply.messageId() != 0x04
                            || reply.payload().length < 40) continue;
                    byte[] payload = reply.payload();
                    detectedFirmware = ascii(payload, 0, 30);
                    for (int offset = 40; offset + 30 <= payload.length; offset += 30) {
                        String extension = ascii(payload, offset, 30);
                        if (extension.startsWith("MOD=")) detectedModel = extension.substring(4);
                        if (extension.startsWith("FWVER=")) detectedFirmware = extension.substring(6);
                    }
                }
                if (connection == null) {
                    configuration = new UbloxCaptureConfiguration(this::exchange);
                    configuration.configure();
                }
            }
            worker =
                    Thread.ofPlatform()
                            .name("lnis-gnss-capture")
                            .start(() -> run(settings, chunks, failure, selection, completed, progress));
        } catch (RuntimeException error) {
            try { restoreUbloxConfiguration(); }
            catch (RuntimeException restoreError) { error.addSuppressed(restoreError); }
            if (port != null) {
                try { port.closePort(); } catch (RuntimeException closeError) { error.addSuppressed(closeError); }
            }
            running.set(false);
            throw error;
        }
    }

    private void run(
            Settings settings,
            Consumer<CaptureChunk> chunks,
            Consumer<Throwable> failure,
            SingleEpochCapture selection,
            Runnable completed, Consumer<CaptureProgress> progress) {
        CaptureDiagnostics diagnostics = new CaptureDiagnostics();
        long lastProgress = 0;
        UbloxParser ubx = new UbloxParser();
        UUID testId = UUID.randomUUID();
        long sequence = 0, bytesRead = 0, records = 0, chunkIndex = 0;
        ByteArrayOutputStream rawChunk = new ByteArrayOutputStream(1024 * 1024),
                canonicalChunk = new ByteArrayOutputStream(1024 * 1024);
        boolean selected = false;
        Throwable failed = null;
        long deadline = System.nanoTime() + 120_000_000_000L;
        try {
            // raw-only를 제외한 파일의 첫 record에는 나중에 수집 환경을 추적할 메타데이터를 넣는다.
            if (!"raw-only".equalsIgnoreCase(settings.protocolId)) {
                byte[] metadata =
                        GrawCodec.encode(
                                new GrawCodec.Envelope(
                                        testId,
                                        UUID.randomUUID(),
                                        sequence++,
                                        Instant.now(),
                                        new GrawCodec.ReceiverMetadata(
                                                detectedModel.isBlank() ? settings.receiverModel : detectedModel,
                                                detectedFirmware.isBlank() ? settings.firmwareVersion : detectedFirmware,
                                                settings.portName,
                                                settings.baudRate,
                                                settings.sessionName)));
                if (selection != null) {
                    selection.accept(metadata);
                } else {
                    writeRecord(canonicalChunk, metadata);
                }
                records++;
            }
            if (connection != null && selection != null) {
                for (byte[] record : connection.seed(testId, sequence)) {
                    selection.accept(record);
                    sequence++;
                    records++;
                }
            }
            byte[] buffer = new byte[8192];
            while (running.get()) {
                if (selection != null && System.nanoTime() >= deadline) {
                    var fallback = selection.onTimeout();
                    if (fallback != null) {
                        for (byte[] item : fallback) {
                            writeRecord(canonicalChunk, item);
                        }
                        records = fallback.size();
                        selected = true;
                        running.set(false);
                        break;
                    }
                    throw new IllegalStateException(
                            "120초 안에 유효한 1에폭을 얻지 못했습니다: " + diagnostics.snapshot(System.nanoTime()).message());
                }
                int count = port.readBytes(buffer, buffer.length);
                if (count < 0) {
                    throw new IllegalStateException("Serial read failed");
                }
                long now = System.nanoTime();
                diagnostics.bytes(count, now);
                if (now - lastProgress >= 1_000_000_000L) {
                    progress.accept(diagnostics.snapshot(now));
                    lastProgress = now;
                }
                if (count == 0) continue;
                rawObserver.accept(Arrays.copyOf(buffer, count));
                if (selection == null) {
                    rawChunk.write(buffer, 0, count);
                }
                bytesRead += count;
                // ubx는 검증·변환하고 canonical-v1은 이미 변환된 입력이므로 그대로 누적한다.
                if ("ubx".equalsIgnoreCase(settings.protocolId)) {
                    for (var frame : ubx.push(buffer, count)) {
                        diagnostics.frame(now);
                        var message = UbloxParser.toCanonical(frame);
                        if (message != null) {
                            diagnostics.message(message, now);
                            byte[] record =
                                    GrawCodec.encode(
                                            new GrawCodec.Envelope(
                                                    testId,
                                                    UUID.randomUUID(),
                                                    sequence++,
                                                    Instant.now(),
                                                    message));
                            if (selection != null) {
                                var chosen = selection.accept(record);
                                if (chosen != null) {
                                    for (byte[] item : chosen) {
                                        writeRecord(canonicalChunk, item);
                                    }
                                    records = chosen.size();
                                    selected = true;
                                    running.set(false);
                                    break;
                                }
                            } else {
                                writeRecord(canonicalChunk, record);
                            }
                            records++;
                        }
                    }
                } else if ("lnis-canonical-v1".equalsIgnoreCase(settings.protocolId)) {
                    canonicalChunk.write(buffer, 0, count);
                }
                // raw/canonical 중 하나라도 상한에 도달하면 둘을 함께 비워 같은 시점의 진단 자료를 전달한다.
                if (rawChunk.size() >= 1024 * 1024 || canonicalChunk.size() >= 1024 * 1024) {
                    chunks.accept(
                            new CaptureChunk(
                                    chunkIndex++,
                                    drain(rawChunk),
                                    drain(canonicalChunk),
                                    bytesRead,
                                    records));
                }
            }
            if (selection != null && !selected) {
                throw new IllegalStateException("한 시점 수집이 중단되었습니다.");
            }
            if (rawChunk.size() > 0 || canonicalChunk.size() > 0) {
                chunks.accept(
                        new CaptureChunk(
                                chunkIndex,
                                drain(rawChunk),
                                drain(canonicalChunk),
                                bytesRead,
                                records));
            }
        } catch (Throwable error) {
            failed = error;
        } finally {
            try {
                restoreUbloxConfiguration();
            } catch (Throwable error) {
                if (failed == null) {
                    failed = error;
                }
            } finally {
                if (port != null) {
                    try { port.closePort(); } catch (RuntimeException closeError) { if (failed == null) failed = closeError; }
                }
                running.set(false);
            }
        }
        if (failed != null) {
            failure.accept(failed);
        } else if (selected) {
            try {
                completed.run();
            } catch (Throwable error) {
                failure.accept(error);
            }
        }
    }

    private static void writeRecord(ByteArrayOutputStream out, byte[] record) {
        out.writeBytes(
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(record.length).array());
        out.writeBytes(record);
    }

    private static byte[] drain(ByteArrayOutputStream out) {
        byte[] bytes = out.toByteArray();
        out.reset();
        return bytes;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    public synchronized void stop() {
        running.set(false);
    }

    /** DTN 입력 확정 전 마지막 청크 전달과 COM 정리가 끝났는지 확인한다. */
    public void stopAndAwait() throws InterruptedException {
        stop();
        Thread current = worker;
        if (current != null && current != Thread.currentThread()) {
            current.join(10000);
            if (current.isAlive()) {
                throw new IllegalStateException("GNSS 수집 종료 대기 시간 초과");
            }
        }
    }

    /** Bounded request/reply exchange; keep a complete raw diagnostic stream. */
    private List<UbloxParser.UbxFrame> exchange(byte[] request) {
        write(request);
        UbloxParser parser = new UbloxParser();
        List<UbloxParser.UbxFrame> replies = new ArrayList<>();
        long deadline = System.nanoTime() + 1_500_000_000L;
        byte[] buffer = new byte[8192];
        while (System.nanoTime() < deadline) {
            int count = port.readBytes(buffer, buffer.length);
            if (count < 0) throw new IllegalStateException("수신기 설정 조회 중 직렬 연결 끊김");
            if (count == 0) continue;
            rawObserver.accept(Arrays.copyOf(buffer, count));
            var received = parser.push(buffer, count);
            replies.addAll(received);
            if (received.stream().anyMatch(f -> matchesReply(request, f))) break;
        }
        return replies;
    }

    /** Polls must wait for data; a delayed ACK from an earlier CFG-MSG is not its response. */
    static boolean matchesReply(byte[] request, UbloxParser.UbxFrame reply) {
        int payloadLength = Byte.toUnsignedInt(request[4]) | Byte.toUnsignedInt(request[5]) << 8;
        boolean ratePoll = request[2] == 6 && request[3] == 1 && payloadLength == 2;
        boolean poll = payloadLength == 0 || ratePoll;
        if (reply.messageClass() == Byte.toUnsignedInt(request[2])
                && reply.messageId() == Byte.toUnsignedInt(request[3])) {
            return !ratePoll || (reply.payload().length >= 2
                    && reply.payload()[0] == request[6] && reply.payload()[1] == request[7]);
        }
        return !poll && reply.messageClass() == 5 && reply.payload().length == 2
                && reply.payload()[0] == request[2] && reply.payload()[1] == request[3];
    }

    private void restoreUbloxConfiguration() {
        if (configuration != null && port != null && port.isOpen()) configuration.restore();
        configuration = null;
    }

    private void write(byte[] value) {
        if (port.writeBytes(value, value.length) != value.length) {
            throw new IllegalStateException("Unable to configure u-blox receiver");
        }
    }

    @Override
    public void close() {
        try {
            stopAndAwait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
