package server.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import server.gnss.GrawCodec;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 공통 GRAW codec의 인코딩·디코딩 호환성을 검증한다. */
class CodecTests {
    @Test
    void crcMatchesKnownVector() {
        assertEquals(0xcbf43926L, Hashing.crc32("123456789".getBytes()));
    }

    @Test
    void observationGrawRoundTrips() {
        var message =
                new GrawCodec.ObservationEpoch(
                        123.5,
                        2300,
                        18,
                        1,
                        1,
                        List.of(
                                new GrawCodec.Observation(
                                        10.25, 20.5, -3.5f, 0, 8, 1, 0, 55, 45, 1, 2, 3, 7)));
        var envelope =
                new GrawCodec.Envelope(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        9,
                        Instant.parse("2026-01-01T00:00:00.123456Z"),
                        message);
        var decoded = GrawCodec.decode(GrawCodec.encode(envelope));
        assertEquals(envelope.testId(), decoded.testId());
        assertEquals(message, decoded.message());
        assertEquals(envelope.capturedAt(), decoded.capturedAt());
    }
}
