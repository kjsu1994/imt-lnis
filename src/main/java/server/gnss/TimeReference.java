package server.gnss;

import java.net.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** USB 수신 지점의 UTC 추정. PC 시간을 변경하지 않으며 PPS 정확도를 주장하지 않는다. */
public final class TimeReference {
    public record Reading(boolean ready, Instant utc, int samples, Double spreadSeconds) {}
    private record Sample(Instant utc, long tick) {}
    private final Deque<Sample> samples = new ArrayDeque<>();

    public synchronized void reset() {
        samples.clear();
    }

    public synchronized void observe(UbloxParser.UbxFrame frame, long tick) {
        if (frame.messageClass() != 1 || frame.messageId() != 0x21 || frame.payload().length != 20) {
            return;
        }
        ByteBuffer data = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
        if ((data.get(19) & 7) != 7) {
            reset();
            return;
        }
        try {
            Instant utc = LocalDateTime.of(Short.toUnsignedInt(data.getShort(12)),
                    Byte.toUnsignedInt(data.get(14)), Byte.toUnsignedInt(data.get(15)),
                    Byte.toUnsignedInt(data.get(16)), Byte.toUnsignedInt(data.get(17)),
                    Byte.toUnsignedInt(data.get(18))).toInstant(ZoneOffset.UTC).plusNanos(data.getInt(8));
            Sample previous = samples.peekLast();
            if (previous != null && utc.equals(previous.utc())) {
                return;
            }
            if (previous != null && (!utc.isAfter(previous.utc())
                    || Math.abs(seconds(previous.utc(), utc) - (tick - previous.tick()) / 1e9) > 0.1)) {
                samples.clear();
            }
            samples.addLast(new Sample(utc, tick));
            while (samples.size() > 8) {
                samples.removeFirst();
            }
        } catch (DateTimeException invalid) {
            reset();
        }
    }

    public synchronized Reading reading() {
        return reading(System.nanoTime());
    }

    synchronized Reading reading(long now) {
        if (samples.isEmpty()) {
            return new Reading(false, null, 0, null);
        }
        Sample base = samples.getLast();
        double[] offsets = samples.stream().mapToDouble(s ->
                seconds(base.utc(), s.utc()) + (base.tick() - s.tick()) / 1e9).sorted().toArray();
        double spread = offsets[offsets.length - 1] - offsets[0];
        boolean ready = samples.size() >= 5 && now - base.tick() >= 0
                && now - base.tick() < TimeUnit.SECONDS.toNanos(3) && spread <= 0.05;
        Instant utc = base.utc().plusNanos(now - base.tick())
                .plusNanos(Math.round(offsets[offsets.length / 2] * 1e9));
        return new Reading(ready, ready ? utc : null, samples.size(), spread);
    }

    public static double seconds(Instant from, Instant to) {
        Duration duration = Duration.between(from, to);
        return duration.getSeconds() + duration.getNano() / 1e9;
    }

    public record NetworkSample(double offsetSeconds, double roundTripSeconds) {}

    /** RFC 5905의 4개 시각. 네트워크 비대칭 오차는 제거할 수 없다. */
    public static NetworkSample exchange(Instant t1, Instant t2, Instant t3, Instant t4, double elapsed) {
        double processing = seconds(t2, t3);
        double roundTrip = elapsed - processing;
        if (!Double.isFinite(elapsed) || elapsed < 0 || elapsed > 5 || processing < 0
                || roundTrip < -0.001 || Math.abs(seconds(t1, t4) - elapsed) > 0.05) {
            throw new IllegalStateException("시각 조회 중 시계 변경 또는 전달 시간 이상");
        }
        return new NetworkSample((seconds(t1, t2) + seconds(t4, t3)) / 2, Math.max(0, roundTrip));
    }

    /** 읽기 전용 SNTP 조회. 임의 주소는 받지 않고 서버 설정의 공통 NTP만 사용한다. */
    public static NetworkSample ntp(String host) throws Exception {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("공통 NTP 서버가 설정되지 않았습니다.");
        }
        try (var socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(host, 123));
            socket.setSoTimeout(1500);
            byte[] request = new byte[48];
            request[0] = 0x23;
            Instant start = Instant.now();
            long tick = System.nanoTime();
            ByteBuffer bytes = ByteBuffer.wrap(request);
            bytes.putInt(40, (int) (start.getEpochSecond() + 2208988800L));
            bytes.putInt(44, (int) (start.getNano() * 4294967296L / 1_000_000_000L));
            socket.send(new DatagramPacket(request, request.length));
            byte[] response = new byte[512];
            var packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            Instant end = Instant.now();
            double elapsed = (System.nanoTime() - tick) / 1e9;
            validateNtp(request, response, packet.getLength());
            return exchange(start, ntpTime(response, 32, start), ntpTime(response, 40, start), end, elapsed);
        }
    }

    static void validateNtp(byte[] request, byte[] response, int length) {
        int version = (response[0] >>> 3) & 7;
        if (length < 48 || (response[0] & 7) != 4 || (version != 3 && version != 4)
                || (response[0] & 0xc0) == 0xc0 || Byte.toUnsignedInt(response[1]) < 1
                || Byte.toUnsignedInt(response[1]) > 15
                || !Arrays.equals(request, 40, 48, response, 24, 32)) {
            throw new IllegalStateException("유효하지 않은 NTP 응답");
        }
    }

    private static Instant ntpTime(byte[] data, int at, Instant near) {
        ByteBuffer bytes = ByteBuffer.wrap(data);
        long seconds = Integer.toUnsignedLong(bytes.getInt(at));
        long fraction = Integer.toUnsignedLong(bytes.getInt(at + 4));
        if (seconds == 0 && fraction == 0) {
            throw new IllegalStateException("NTP 시각 누락");
        }
        long nearNtp = near.getEpochSecond() + 2208988800L;
        seconds += Math.round((nearNtp - seconds) / 4294967296.0) * 4294967296L;
        return Instant.ofEpochSecond(seconds - 2208988800L, fraction * 1_000_000_000L / 4294967296L);
    }
}
