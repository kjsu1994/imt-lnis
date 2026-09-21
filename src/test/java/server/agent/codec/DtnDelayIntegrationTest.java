package server.agent.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import server.agent.dtn.DtnProcessor;
import server.central.dtn.DtnComparison;
import server.shared.codec.DtnDelay;
import server.shared.codec.GrawCodec;
import server.shared.codec.NativePvtCodec;

@EnabledOnOs(OS.WINDOWS)
class DtnDelayIntegrationTest {
    private final Path nativeDirectory = Path.of(
            System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
    private final Instant started = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void realNativeRawAndAfsMatchDirectSolverWithoutChangingOriginal() throws Exception {
        byte[] source = NativePvtIntegrationTest.validSample();
        var records = GrawCodec.splitLengthPrefixed(source);
        try (var codec = NativeAfsCodec.load(nativeDirectory)) {
            var processor = new DtnProcessor(codec, nativeDirectory);
            for (boolean raw : List.of(true, false)) {
                UUID id = UUID.randomUUID();
                var prepared = processor.prepare(id, source, raw);
                var reference = prepared.getPvt();
                assertTrue(reference.getFirst().isPositionValid());
                String originalHash = prepared.getTransfer().getSourceSha256();
                for (long nanos : List.of(0L, 1_000_000L, 2_000_000_000L)) {
                    var timing = new DtnDelay.Timing(started, started.plusNanos(nanos));
                    var received = processor.receive(id, prepared.getTransfer(), (stage, message) -> {}, timing);
                    var converted = DtnDelay.convert(records, timing);
                    try (var solver = new NativePvtCodec(nativeDirectory)) {
                        assertEquals(solver.calculate(converted.records()), received.getPvt());
                    }
                    assertEquals(originalHash, prepared.getTransfer().getSourceSha256());
                    assertEquals(1, received.getPvt().size());
                    if (nanos == 0) {
                        assertEquals(reference, received.getPvt());
                    }
                    assertTrue(received.getPvt().getFirst().isPositionValid(), received.getPvt().getFirst().getMessage());
                    assertEquals("MEASURED", DtnComparison.delay(reference, received.getPvt(), received.getDelayEvidence()).get("verdict"));
                    var originalEpoch = (GrawCodec.ObservationEpoch) GrawCodec.decode(records.getLast()).message();
                    var changedEpoch = (GrawCodec.ObservationEpoch) GrawCodec.decode(converted.records().getLast()).message();
                    for (int i = 0; i < originalEpoch.observations().size(); i++) {
                        var a = originalEpoch.observations().get(i);
                        var b = changedEpoch.observations().get(i);
                        assertEquals(a.pseudorangeMeters() + DtnDelay.C * nanos / 1e9, b.pseudorangeMeters(), 1e-7);
                        assertEquals(a.dopplerHz(), b.dopplerHz());
                        assertEquals(a.carrierToNoiseDbHz(), b.carrierToNoiseDbHz());
                        assertEquals(a.carrierPhaseCycles(), b.carrierPhaseCycles());
                        assertEquals(a.trackingStatus(), b.trackingStatus());
                    }
                    for (int i = 0; i < records.size() - 1; i++) {
                        assertArrayEquals(records.get(i), converted.records().get(i));
                    }
                }
                var negative = processor.receive(id, prepared.getTransfer(), (stage, message) -> {},
                        new DtnDelay.Timing(started, started.minusMillis(1)));
                assertFalse(negative.getPvt().getFirst().isPositionValid());
                assertNotNull(negative.getDelayEvidence().error());
                assertEquals("INCONCLUSIVE", DtnComparison.delay(reference, negative.getPvt(), negative.getDelayEvidence()).get("verdict"));
                assertEquals(reference, processor.receive(id, prepared.getTransfer()).getPvt(), "기존 복원 경로 유지");
            }
        }
        assertArrayEquals(source, NativePvtIntegrationTest.validSample(), "원본 GRAW 불변");
    }

    @Test
    void selectedEpochPreservesOnlyPrecedingNavigationAndRejectsStaleSelection() throws Exception {
        var records = new ArrayList<>(GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()));
        int first = records.size() - 1;
        records.add(records.getLast());
        records.add(records.getFirst()); // 이후 항법정보는 선택 시 제외
        var selected = new DtnDelay.Epoch(first + 1, 2400, 100000);
        var subset = GrawCodec.splitLengthPrefixed(DtnDelay.select(records, selected));
        assertEquals(first + 1, subset.size());
        assertEquals(1, subset.stream().map(GrawCodec::decode)
                .filter(e -> e.message() instanceof GrawCodec.ObservationEpoch).count());
        for (int i = 0; i < first; i++) {
            assertArrayEquals(records.get(i), subset.get(i));
        }
        assertThrows(IllegalArgumentException.class,
                () -> DtnDelay.select(records, new DtnDelay.Epoch(first, 2400, 99999)));
        assertEquals(new DtnDelay.Time(2401, 0.5), DtnDelay.shift(2400, 604799.5, 1));
        assertEquals(new DtnDelay.Time(2399, 604799.5), DtnDelay.shift(2400, 0.5, -1));
    }
}
