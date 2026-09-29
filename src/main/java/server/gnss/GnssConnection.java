package server.gnss;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** 한 노드에서 직렬 포트를 한 번만 연다. 시간 감시와 수집은 동일한 스트림을 사용한다.
 * PC 시계는 변경하지 않으며, USB 메시지 도착 시각을 GNSS 정확도로 취급하지 않는다. */
public final class GnssConnection implements AutoCloseable {
    public record Status(String state, String portName, int baudRate, boolean capturing,
                         String timeState, Instant utc, Instant updatedAt, Long accuracyNanos,
                         String message, boolean dtrEnabled, boolean rtsEnabled) {}
    private record Navigation(GrawCodec.NavigationUpdate message, Instant receivedAt, long tick) {}

    private final Supplier<List<SerialCaptureService.DetectedPort>> detector;
    private final Function<SerialCaptureService.Settings, SerialConnection> opener;
    private final Deque<Navigation> navigation = new ArrayDeque<>();
    private final Object writeLock = new Object();
    private volatile boolean requested;
    private volatile String state = "DISCONNECTED", message = "시스템 시간 사용 · GNSS 시간 미확인";
    private volatile SerialConnection physical;
    private volatile Thread reader;
    private SerialCaptureService.Settings settings;
    private String identity = "";
    private CaptureLease lease;
    private Instant utc, updatedAt;
    private Long accuracyNanos;
    private long validTick;
    private boolean utcValid;
    private final boolean collectNavigation;

    public GnssConnection() {
        this(false);
    }

    public GnssConnection(boolean collectNavigation) {
        this.collectNavigation = collectNavigation;
        WindowsSerialClient bridge = WindowsSerialClient.configured();
        detector = bridge == null ? SerialConnection::localPorts : bridge::ports;
        opener = bridge == null ? SerialConnection::local : bridge::open;
    }

    GnssConnection(Supplier<List<SerialCaptureService.DetectedPort>> detector,
                   Function<SerialCaptureService.Settings, SerialConnection> opener) {
        this.detector = detector;
        this.opener = opener;
        this.collectNavigation = false;
    }

    public List<SerialCaptureService.DetectedPort> ports() {
        return detector.get();
    }

    public synchronized Status status() {
        String time = !"CONNECTED".equals(state) ? "UNAVAILABLE"
                : updatedAt == null ? "ACQUIRING"
                : System.nanoTime() - validTick > TimeUnit.SECONDS.toNanos(5) ? "STALE"
                : utcValid ? "VALID" : "ACQUIRING";
        return new Status(state, settings == null ? "" : settings.portName(),
                settings == null ? 38400 : settings.baudRate(), lease != null, time,
                utc, updatedAt, accuracyNanos, message,
                settings != null && settings.dtrEnabled(), settings != null && settings.rtsEnabled());
    }

    public synchronized Status connect(SerialCaptureService.Settings requestedSettings) {
        if (requested || reader != null && reader.isAlive()) {
            throw new IllegalStateException("이미 연결되어 있거나 연결 종료 중입니다. 먼저 연결을 해제하세요.");
        }
        if (requestedSettings == null || requestedSettings.portName() == null
                || requestedSettings.baudRate() < 1200 || requestedSettings.baudRate() > 4_000_000
                || !"UBX".equalsIgnoreCase(requestedSettings.protocolId())) {
            throw new IllegalArgumentException("포트와 통신 속도를 확인하세요.");
        }
        var selected = ports().stream().filter(p -> p.name().equals(requestedSettings.portName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("선택한 포트가 연결되어 있지 않습니다."));
        settings = requestedSettings;
        identity = selected.identity();
        requested = true;
        state = "CONNECTING";
        message = "포트 연결 중";
        resetInput();
        reader = Thread.ofPlatform().name("lnis-gnss-connection").start(this::readLoop);
        return status();
    }

    private void readLoop() {
        boolean connectedBefore = false;
        int retrySeconds = 2;
        try {
            while (requested) {
                UbloxCaptureConfiguration configuration = null;
                try {
                    if (connectedBefore) {
                        var matches = ports().stream().filter(p -> !identity.isBlank() && identity.equals(p.identity())).toList();
                        if (matches.size() != 1) {
                            throw new IllegalStateException("동일 GNSS 장치를 기다리는 중 · 다른 포트로 자동 전환하지 않습니다.");
                        }
                        synchronized (this) {
                            var previous = settings;
                            settings = new SerialCaptureService.Settings(matches.getFirst().name(), previous.baudRate(),
                                    "UBX", "", "", "", previous.dtrEnabled(), previous.rtsEnabled(), false);
                        }
                    }
                    SerialConnection opened = opener.apply(settings);
                    physical = opened;
                    if (!requested) {
                        break;
                    }
                    if (collectNavigation) {
                        configuration = new UbloxCaptureConfiguration(this::exchange);
                        configuration.configure();
                    }
                    connectedBefore = true;
                    synchronized (this) {
                        resetInput();
                        state = "CONNECTED";
                        message = "GNSS 포트 연결됨";
                    }
                    var parser = new UbloxParser();
                    byte[] buffer = new byte[8192];
                    long lastPoll = 0, connectedTick = System.nanoTime();
                    while (requested) {
                        long now = System.nanoTime();
                        if (now - lastPoll >= TimeUnit.SECONDS.toNanos(1)) {
                            // NAV-TIMEUTC poll만 사용한다. 시간 출력률/영구 설정을 변경하지 않는다.
                            byte[] poll = UbloxParser.command(1, 0x21, new byte[0]);
                            if (write(opened, poll, poll.length) != poll.length) {
                                throw new IllegalStateException("GNSS 시간 조회 실패");
                            }
                            lastPoll = now;
                        }
                        int count = opened.readBytes(buffer, buffer.length);
                        if (count < 0 || !opened.isOpen()) {
                            throw new IllegalStateException("GNSS 연결이 끊겼습니다.");
                        }
                        if (count > 0) {
                            for (var frame : parser.push(buffer, count)) {
                                observe(frame, Instant.now(), System.nanoTime());
                            }
                            synchronized (this) {
                                if (lease != null && !lease.queue.offer(Arrays.copyOf(buffer, count))) {
                                    lease.failed = true;
                                    message = "수집 처리 지연 · 수집 버퍼 초과";
                                }
                            }
                        }
                        if (now - connectedTick > TimeUnit.SECONDS.toNanos(30)) {
                            retrySeconds = 2;
                        }
                    }
                } catch (Exception error) {
                    synchronized (this) {
                        resetInput();
                        if (lease != null) {
                            lease.failed = true;
                        }
                        message = Objects.toString(error.getMessage(), "GNSS 연결 오류");
                        if (!connectedBefore || identity.isBlank()) {
                            requested = false;
                            state = "ERROR";
                            message += " · 포트를 확인하고 수동 재연결하세요.";
                        } else {
                            state = "RECONNECTING";
                        }
                    }
                } finally {
                    SerialConnection current = physical;
                    if (!requested && configuration != null && current != null && current.isOpen()) {
                        try {
                            configuration.restore();
                        } catch (RuntimeException failure) {
                            state = "ERROR";
                            message = "GNSS 출력 설정 복원 실패 · 수신기 설정을 확인하세요.";
                        }
                    }
                    physical = null;
                    if (current != null) {
                        try { current.closePort(); } catch (RuntimeException ignored) { }
                    }
                }
                if (requested) {
                    try { Thread.sleep(retrySeconds * 1000L); }
                    catch (InterruptedException stopped) { break; }
                    retrySeconds = Math.min(30, retrySeconds * 2);
                }
            }
        } finally {
            synchronized (this) {
                if (!"ERROR".equals(state)) {
                    state = "DISCONNECTED";
                    message = "시스템 시간 사용 · GNSS 시간 미확인";
                }
                requested = false;
                resetInput();
            }
        }
    }

    private int write(SerialConnection source, byte[] bytes, int length) {
        synchronized (writeLock) {
            return source.writeBytes(bytes, length);
        }
    }

    private List<UbloxParser.UbxFrame> exchange(byte[] command) {
        SerialConnection current = physical;
        if (write(current, command, command.length) != command.length) {
            throw new IllegalStateException("수신기 출력 설정 요청 실패");
        }
        var parser = new UbloxParser();
        var replies = new ArrayList<UbloxParser.UbxFrame>();
        byte[] buffer = new byte[8192];
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        while (System.nanoTime() < deadline) {
            int count = current.readBytes(buffer, buffer.length);
            if (count < 0) {
                throw new IllegalStateException("수신기 설정 중 연결 끊김");
            }
            var frames = parser.push(buffer, count);
            replies.addAll(frames);
            if (frames.stream().anyMatch(frame -> SerialCaptureService.matchesReply(command, frame))) {
                break;
            }
        }
        return replies;
    }

    synchronized void observe(UbloxParser.UbxFrame frame, Instant receivedAt, long tick) {
        if (frame.messageClass() == 1 && frame.messageId() == 0x21 && frame.payload().length == 20) {
            ByteBuffer data = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
            updatedAt = receivedAt;
            validTick = tick;
            utcValid = (Byte.toUnsignedInt(data.get(19)) & 7) == 7;
            utc = null;
            accuracyNanos = Integer.toUnsignedLong(data.getInt(4));
            if (utcValid) {
                try {
                    // 윤초 60초 등 Instant로 안전하게 표현할 수 없는 메시지는 유효로 표시하지 않는다.
                    utc = LocalDateTime.of(Short.toUnsignedInt(data.getShort(12)),
                            Byte.toUnsignedInt(data.get(14)), Byte.toUnsignedInt(data.get(15)),
                            Byte.toUnsignedInt(data.get(16)), Byte.toUnsignedInt(data.get(17)),
                            Byte.toUnsignedInt(data.get(18))).toInstant(ZoneOffset.UTC).plusNanos(data.getInt(8));
                } catch (java.time.DateTimeException invalid) {
                    utcValid = false;
                }
            }
        }
        if (frame.messageClass() == 2 && frame.messageId() == 0x13) {
            try {
                var nav = (GrawCodec.NavigationUpdate) UbloxParser.toCanonical(frame);
                navigation.addLast(new Navigation(nav, receivedAt, tick));
                trimNavigation(tick);
            } catch (IllegalArgumentException malformed) {
                // 잘못된 항법 메시지가 시간 감시나 포트 연결을 종료시키지 않는다.
            }
        }
    }

    private void trimNavigation(long now) {
        // 연속 수신의 메모리를 제한한다. 최종 항법 나이/일관성 판단은 기존 PVT 경로가 수행한다.
        while (!navigation.isEmpty() && (navigation.size() > 512
                || now - navigation.getFirst().tick() > TimeUnit.SECONDS.toNanos(120))) {
            navigation.removeFirst();
        }
    }

    private void resetInput() {
        navigation.clear();
        utc = null;
        updatedAt = null;
        accuracyNanos = null;
        utcValid = false;
    }

    synchronized SerialConnection acquire(SerialCaptureService.Settings capture) {
        if (!"CONNECTED".equals(state) || physical == null || !requested) {
            throw new IllegalStateException("GNSS 포트를 먼저 연결하세요.");
        }
        if (lease != null) {
            throw new IllegalStateException("이미 GNSS 수집 중입니다.");
        }
        if (!settings.portName().equals(capture.portName()) || settings.baudRate() != capture.baudRate()
                || settings.dtrEnabled() != capture.dtrEnabled() || settings.rtsEnabled() != capture.rtsEnabled()
                || !"UBX".equalsIgnoreCase(capture.protocolId())) {
            throw new IllegalArgumentException("연결된 포트·통신 설정과 수집 설정이 다릅니다.");
        }
        trimNavigation(System.nanoTime());
        lease = new CaptureLease(physical, List.copyOf(navigation));
        return lease;
    }

    synchronized List<byte[]> seed(UUID testId, long firstSequence) {
        if (lease == null) {
            return List.of();
        }
        var records = new ArrayList<byte[]>();
        for (Navigation nav : lease.seed) {
            records.add(GrawCodec.encode(new GrawCodec.Envelope(testId, UUID.randomUUID(),
                    firstSequence++, nav.receivedAt(), nav.message())));
        }
        return records;
    }

    public void disconnect() {
        Thread thread;
        synchronized (this) {
            if (lease != null) {
                throw new IllegalStateException("수집을 먼저 중단한 뒤 연결을 해제하세요.");
            }
            requested = false;
            thread = reader;
            if (thread != null && "RECONNECTING".equals(state)) {
                thread.interrupt();
            }
        }
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(10000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (thread.isAlive()) {
                throw new IllegalStateException("연결 종료 중입니다. 잠시 후 상태를 확인하세요.");
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (lease != null) {
                lease.closePort();
            }
        }
        disconnect();
    }

    private final class CaptureLease implements SerialConnection {
        private final SerialConnection source;
        private final List<Navigation> seed;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(128);
        private volatile boolean open = true, failed;
        CaptureLease(SerialConnection source, List<Navigation> seed) {
            this.source = source;
            this.seed = seed;
        }
        public int readBytes(byte[] buffer, int length) {
            if (!isOpen()) {
                return -1;
            }
            try {
                byte[] bytes = queue.poll(500, TimeUnit.MILLISECONDS);
                if (!isOpen()) {
                    return -1;
                }
                if (bytes == null) {
                    return 0;
                }
                if (bytes.length > length) {
                    failed = true;
                    return -1;
                }
                System.arraycopy(bytes, 0, buffer, 0, bytes.length);
                return bytes.length;
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        public int writeBytes(byte[] bytes, int length) {
            return isOpen() ? write(source, bytes, length) : -1;
        }
        public boolean isOpen() {
            return open && !failed && requested && source == physical && "CONNECTED".equals(state);
        }
        public void closePort() {
            synchronized (GnssConnection.this) {
                open = false;
                queue.clear();
                if (lease == this) {
                    lease = null;
                }
            }
        }
    }
}
