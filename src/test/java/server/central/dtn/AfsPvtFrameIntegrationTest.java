package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import server.agent.codec.NativeAfsCodec;
import server.agent.codec.NativePvtIntegrationTest;
import server.agent.dtn.DtnProcessor;
import server.shared.codec.*;
import server.shared.codec.GrawCodec.*;
import server.shared.model.DtnModels.*;

@EnabledOnOs(OS.WINDOWS)
class AfsPvtFrameIntegrationTest {
    private final Path nativePath = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private List<byte[]> sample() throws Exception { return GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()); }

    @Test void framesAloneReproducePvtAndFitOneFramePerObservedSatellite() throws Exception {
        var source = sample();
        var frames = AfsPvtFrameCodec.select(source);
        assertEquals(5, frames.size());
        var decoded = new ArrayList<AfsPvtFrameCodec.Payload>();
        try (var afs = NativeAfsCodec.load(nativePath); var reference = new NativePvtCodec(nativePath);
                var receiver = new NativePvtCodec(nativePath)) {
            for (var p : frames) {
                var bits = AfsPvtFrameCodec.encode(p);
                assertEquals(846, bits.sb3().length);
                assertEquals(846, bits.sb4().length);
                for (int i = AfsPvtFrameCodec.SB3_USED; i < 846; i++) assertEquals(0, bits.sb3()[i]);
                for (int i = AfsPvtFrameCodec.SB4_USED; i < 846; i++) assertEquals(0, bits.sb4()[i]);
                byte[] encoded = afs.encode(33, bits.sb2(), bits.sb3(), bits.sb4());
                assertEquals(750, encoded.length);
                var recovered = afs.decode(33, encoded);
                assertTrue(recovered.sb2Valid() && recovered.sb3Valid() && recovered.sb4Valid());
                decoded.add(AfsPvtFrameCodec.decode(recovered.sb2(), recovered.sb3(), recovered.sb4()));
            }
            assertEquals(reference.calculate(source), receiver.calculate(AfsPvtFrameCodec.records(decoded)));
        }
    }

    @Test void oldNavigationOriginalBytesAndNonGpsObservationsRemainInMetadata() throws Exception {
        var source = sample();
        try (var afs = NativeAfsCodec.load(nativePath)) {
            var processor = new DtnProcessor(afs, nativePath);
            UUID id = UUID.randomUUID();
            var prepared = processor.prepare(id, NativePvtIntegrationTest.validSample());
            Transfer transfer = json.readValue(json.writeValueAsBytes(prepared.getTransfer()), Transfer.class);
            assertEquals(4, transfer.getSchemaVersion());
            assertEquals(AfsPvtFrameCodec.FORMAT, transfer.getFormat());
            assertEquals(96, transfer.getSatellites().stream().mapToInt(s -> s.metadata().navigationSupplement().size()).sum());
            assertEquals(5, transfer.getSatellites().stream().mapToInt(s -> s.frames().size()).sum());
            var result = processor.receive(id, transfer);
            assertEquals(prepared.getPvt(), result.getPvt());
            assertEquals(server.shared.model.DtnObservationView.fromRecords(source).records(), result.getObservations().records());
            assertEquals("AFS_V4", result.getObservations().frameInput().source());
            Frame frame = transfer.getSatellites().stream().flatMap(s -> s.frames().stream()).findFirst().orElseThrow();
            var bits = afs.decode(frame.getToi(), Base64.getDecoder().decode(frame.getFrameBase64()));
            bits.sb4()[200] ^= 1; // Recompute CRC/FEC: a syntactically valid but changed observation must still be rejected.
            frame.setFrameBase64(Base64.getEncoder().encodeToString(afs.encode(frame.getToi(), bits.sb2(), bits.sb3(), bits.sb4())));
            assertThrows(IllegalArgumentException.class, () -> processor.receive(id, transfer));
        }
    }

    @Test void preservesEpochOrderAndLastCoherentEphemerisDuringIncompleteUpdate() throws Exception {
        var records = new ArrayList<>(sample());
        var sourceNav = GrawCodec.decode(records.get(54));
        var nav = (NavigationUpdate) sourceNav.message();
        var words = new ArrayList<>(nav.words());
        words.set(7, words.get(7) ^ (1L << 29)); // Change IODC: do not replace the last coherent ephemeris.
        records.add(GrawCodec.encode(new Envelope(sourceNav.testId(), sourceNav.messageId(), 100, sourceNav.capturedAt(),
                new NavigationUpdate(0, nav.satelliteId(), 0, 0, 2, words))));
        records.add(records.get(96));
        var payloads = AfsPvtFrameCodec.select(records);
        assertEquals(10, payloads.size());
        assertEquals(0, payloads.getFirst().epoch());
        assertEquals(1, payloads.getLast().epoch());
        try (var a = new NativePvtCodec(nativePath); var b = new NativePvtCodec(nativePath)) {
            assertEquals(a.calculate(records), b.calculate(AfsPvtFrameCodec.records(payloads)));
        }
    }

    @Test void capturesGlobalIonosphereEvenFromAnUnobservedPrn() throws Exception {
        var records = new ArrayList<>(sample());
        var envelope = GrawCodec.decode(records.getFirst());
        var words = new ArrayList<Long>(Collections.nCopies(10, 0L));
        words.set(0, 0x8b0000L << 6);
        words.set(1, (4L << 2) << 6);
        words.set(2, ((1L << 22) | (56L << 16) | 0x1234) << 6);
        records.add(records.size() - 1, GrawCodec.encode(new Envelope(envelope.testId(), UUID.randomUUID(), 98,
                envelope.capturedAt(), new NavigationUpdate(0, 1, 0, 0, 2, words))));
        var frames = AfsPvtFrameCodec.select(records);
        assertTrue(frames.stream().allMatch(p -> p.ionPrn() == 1));
        try (var a = new NativePvtCodec(nativePath); var b = new NativePvtCodec(nativePath)) {
            assertEquals(a.calculate(records), b.calculate(AfsPvtFrameCodec.records(frames)));
        }
    }

    @Test void rejectsMismatchedSubblocksAndReservedBits() throws Exception {
        var frames = AfsPvtFrameCodec.select(sample());
        var a = AfsPvtFrameCodec.encode(frames.get(0));
        var b = AfsPvtFrameCodec.encode(frames.get(1));
        assertThrows(IllegalArgumentException.class, () -> AfsPvtFrameCodec.decode(a.sb2(), a.sb3(), b.sb4()));
        a.sb3()[845] = 1;
        assertThrows(IllegalArgumentException.class, () -> AfsPvtFrameCodec.decode(a.sb2(), a.sb3(), a.sb4()));
    }

    @Test void preparesReproducibleNativeIqFixture() throws Exception {
        var records = sample();
        try (var solver = new NativePvtCodec(nativePath)) {
            String input = IqService.earthInput(records, solver.calculate(records).getFirst());
            Path root = Path.of("build/afs-v4-verification");
            Files.createDirectories(root);
            Files.writeString(root.resolve("earth-input.txt"), input);
            Files.write(root.resolve("source.graw"), NativePvtIntegrationTest.validSample());
            var source = IqReceiver.source(input, "A".repeat(64));
            assertEquals(IqReceiver.FRAME_METHOD, source.metadata().pvtMethod());
            json.writeValue(root.resolve("source.json").toFile(), source);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LNIS_IQ_SMOKE_DIRECTORY", matches = ".+")
    void actualNinetySecondIqUsesDecodedNavigationWithoutJsonAssistance() throws Exception {
        Path root = Path.of(System.getenv("LNIS_IQ_SMOKE_DIRECTORY"));
        var source = json.readValue(root.resolve("source.json").toFile(), IqReceiver.Source.class);
        var m = source.metadata();
        var noAssistance = new IqMetadata(m.signal(), m.pvtMethod(), m.week(), m.towSeconds(), m.trajectory(), m.prns(), List.of());
        IqReceiver.validate(noAssistance);
        var receiver = new IqReceiver("unused", nativePath.toString());
        var result = receiver.calculate(root.resolve("tracking.log"), noAssistance);
        assertTrue(result.pvt().stream().anyMatch(Pvt::isPositionValid));
        assertTrue(result.pvt().stream().anyMatch(Pvt::isVelocityValid));
        assertTrue(result.observations().get("assistance").toString().contains("AFS SB2"));
        var reference = IqReceiver.references(m, List.of(source.reference()), result.pvt());
        var comparison = IqReceiver.comparison(reference, result.pvt());
        assertEquals("MEASURED", comparison.get("verdict"));
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("pvt-comparison.json").toFile(), comparison);
    }
}
