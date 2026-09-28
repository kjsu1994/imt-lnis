package server.afs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import server.common.DtnModels;
import server.dtn.DtnProcessor;
import server.gnss.GrawCodec;
import server.pvt.DelayTime;
import server.pvt.DtnDelay;
import server.pvt.NativePvtCodec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class DelayTransferCodecTest {
    private final Path nativeDirectory = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
    private final Instant start = Instant.parse("2026-09-28T00:00:00.123456789Z");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    public void frameAndRawComputeWithoutOriginalOrReference() throws Exception {
        byte[] source = NativePvtIntegrationTest.validSample();
        var records = GrawCodec.splitLengthPrefixed(source);
        try (var codec = NativeAfsCodec.load(nativeDirectory)) {
            var processor = new DtnProcessor(codec, nativeDirectory);
            for (long nanos : List.of(0L, 1_000_000L, 19_222_158_000L, 599_000_000_000L)) {
                var timing = new DtnDelay.Timing(start, start.plusNanos(nanos));
                List<DtnModels.Pvt> outputs = new ArrayList<>();
                for (boolean raw : List.of(true, false)) {
                    UUID id = UUID.randomUUID();
                    var prepared = processor.prepare(id, source, raw, start, (s, m) -> {});
                    var packet = DelayTransferCodec.packet(json, prepared.getTransfer());
                    assertFalse(packet.has("referencePvt"));
                    assertFalse(packet.has("metadata"));
                    assertFalse(packet.toString().contains("pseudorangeMeters"));
                    var transfer = json.treeToValue(packet, DtnModels.Transfer.class);
                    var restored = DelayTransferCodec.restore(transfer, codec, timing);
                    var expected = DtnDelay.convert(records, timing).records();
                    var a = GrawCodec.splitLengthPrefixed(source);
                    var expectedEpoch = (GrawCodec.ObservationEpoch) GrawCodec.decode(expected.getLast()).message();
                    var actualEpoch = (GrawCodec.ObservationEpoch) GrawCodec.decode(restored.records().getLast()).message();
                    assertEquals(expectedEpoch.receiverTowSeconds(), actualEpoch.receiverTowSeconds());
                    for (int i = 0; i < actualEpoch.observations().size(); i++) {
                        double range = expectedEpoch.observations().get(i).pseudorangeMeters();
                        assertEquals(range, actualEpoch.observations().get(i).pseudorangeMeters(),
                                Math.max(1e-6, Math.ulp(range) * 4));
                        assertEquals(expectedEpoch.observations().get(i).dopplerHz(),
                                actualEpoch.observations().get(i).dopplerHz());
                    }
                    var result = processor.receive(id, transfer, (s, m) -> {}, timing);
                    assertTrue(result.getPvt().getFirst().isPositionValid(), result.getPvt().getFirst().getMessage());
                    assertNotNull(result.getObservations().receivedValues());
                    assertNull(result.getDelayEvidence().satellites().getFirst().originalMeters());
                    outputs.add(result.getPvt().getFirst());
                    try (var solver = new NativePvtCodec(nativeDirectory)) {
                        var baseline = solver.calculate(expected).getFirst();
                        assertEquals(baseline.getReceiverClockBiasSeconds(),
                                outputs.getLast().getReceiverClockBiasSeconds(), 1e-9);
                        for (int axis = 0; axis < 3; axis++) {
                            assertEquals(baseline.getEcefMeters()[axis], outputs.getLast().getEcefMeters()[axis], 0.001);
                        }
                    }
                }
                for (int axis = 0; axis < 3; axis++) {
                    assertEquals(outputs.getFirst().getEcefMeters()[axis], outputs.getLast().getEcefMeters()[axis], 0.001);
                }
            }
        }
    }

    @Test
    public void fillerAndTimestampBoundariesAreValidated() throws Exception {
        var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
        var payload = AfsPvtFrameCodec.select(records).getFirst();
        var blocks = AfsPvtFrameCodec.encodeDelay(payload, start);
        assertEquals(506, AfsPvtFrameCodec.SB4_USED);
        for (int i = 517; i < 846; i++) assertEquals((i - 517) % 2, blocks.sb3()[i]);
        for (int i = 670; i < 846; i++) assertEquals((i - 670) % 2, blocks.sb4()[i]);
        var restored = AfsPvtFrameCodec.decodeDelay(blocks.sb2(), blocks.sb3(), blocks.sb4(), start);
        assertEquals(payload.range(), restored.observation().range(), 1e-6);
        blocks.sb4()[845] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> AfsPvtFrameCodec.decodeDelay(blocks.sb2(), blocks.sb3(), blocks.sb4(), start));
        var stamp = DelayTime.transmit(Instant.parse("2026-09-28T00:00:00.001Z"), 22000000);
        assertEquals(22000000, stamp.rangeAt(Instant.parse("2026-09-28T00:00:00.001Z")), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> new DelayTime(0, DelayTime.SCALE));
    }
}
