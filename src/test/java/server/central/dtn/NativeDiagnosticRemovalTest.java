package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import server.shared.model.DtnModels.IqMetadata;
import server.shared.model.DtnModels.Pvt;

/** Replays actual native before/after traces from the same RF files. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "LNIS_NATIVE_LOG_COMPARISON", matches = ".+")
class NativeDiagnosticRemovalTest {
    @Test
    void removingDiagnosticDumpsPreservesFrameDataAndPvt() throws Exception {
        Path root = Path.of(System.getenv("LNIS_NATIVE_LOG_COMPARISON"));
        var json = new ObjectMapper().findAndRegisterModules();
        var receiver = new IqReceiver("unused", System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
        var measurements = new ArrayList<Map<String, Object>>();
        for (String fixture : List.of("prn8", "no8")) {
            String sourceFile = "prn8".equals(fixture) ? "source.json" : "source-no8.json";
            var source = json.readValue(root.resolve(sourceFile).toFile(), IqReceiver.Source.class);
            var metadata = source.metadata();
            // The receiver must obtain navigation from decoded frames, not JSON assistance.
            var frameOnly = new IqMetadata(metadata.signal(), metadata.pvtMethod(), metadata.week(),
                    metadata.towSeconds(), metadata.trajectory(), metadata.prns(), List.of());
            var baseline = receiver.calculate(root.resolve("tracking-" + fixture + "-baseline-1.log"), frameOnly);
            assertTrue(baseline.pvt().size() >= 10, fixture);
            for (int round = 1; round <= 3; round++) {
                Path before = root.resolve("tracking-" + fixture + "-baseline-" + round + ".log");
                Path after = root.resolve("tracking-" + fixture + "-candidate-" + round + ".log");
                assertEquals(frameRecords(before), frameRecords(after), fixture + " decoded frame round " + round);
                compareObservations(before, after);
                measurements.add(comparePvt(fixture + "-baseline-" + round, baseline.pvt(), receiver.calculate(before, frameOnly).pvt()));
                measurements.add(comparePvt(fixture + "-candidate-" + round, baseline.pvt(), receiver.calculate(after, frameOnly).pvt()));
                assertFalse(Files.readString(after).contains("[비교ID="), "Candidate still emitted diagnostic dump");
                if ("prn8".equals(fixture)) {
                    assertTrue(Files.readString(before).contains("[상세로그=2/2]"), "Baseline must exercise both diagnostic dumps");
                }
            }
        }
        Path report = Path.of("build/reports/native-clean/pvt-repetition.json");
        Files.createDirectories(report.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), measurements);
    }

    private List<String> frameRecords(Path path) throws Exception {
        try (var lines = Files.lines(path)) {
            return lines.filter(line -> line.startsWith("$IQAFS,"))
                    .sorted().toList();
        }
    }

    private void compareObservations(Path before, Path after) throws Exception {
        List<String[]> a = observations(before), b = observations(after);
        assertEquals(a.size(), b.size(), "Tracked observation loss");
        for (int i = 0; i < a.size(); i++) {
            for (int field = 0; field < 5; field++) assertEquals(a.get(i)[field], b.get(i)[field]);
            // Native asynchronous acquisition has repeat-to-repeat tracking transients.
            // Accumulated carrier phase has an arbitrary acquisition origin and is not an SPP input.
            double rangeDelta = Math.abs(Double.parseDouble(a.get(i)[5]) - Double.parseDouble(b.get(i)[5])) * 299792458.0;
            double dopplerDelta = Math.abs(Double.parseDouble(a.get(i)[6]) - Double.parseDouble(b.get(i)[6]));
            assertTrue(rangeDelta < 0.25, "Tracked range difference: " + rangeDelta);
            assertTrue(dopplerDelta < 0.25, "Tracked Doppler difference: " + dopplerDelta);
        }
    }

    private List<String[]> observations(Path path) throws Exception {
        try (var lines = Files.lines(path)) {
            return lines.filter(line -> line.startsWith("$IQOBS,")).sorted().map(line -> line.split(",")).toList();
        }
    }

    private Map<String, Object> comparePvt(String name, List<Pvt> reference, List<Pvt> actual) {
        assertEquals(reference.size(), actual.size(), name);
        double position = 0, velocity = 0, clock = 0;
        for (int i = 0; i < reference.size(); i++) {
            Pvt a = reference.get(i), b = actual.get(i);
            assertEquals(a.getWeek(), b.getWeek());
            assertEquals(a.getTowSeconds(), b.getTowSeconds());
            assertTrue(a.isPositionValid() && b.isPositionValid(), name);
            assertTrue(a.isVelocityValid() && b.isVelocityValid(), name);
            assertEquals(a.getSatellitesUsed(), b.getSatellitesUsed());
            position = Math.max(position, distance(a.getEcefMeters(), b.getEcefMeters()));
            velocity = Math.max(velocity, distance(a.getVelocityMetersPerSecond(), b.getVelocityMetersPerSecond()));
            clock = Math.max(clock, Math.abs(a.getReceiverClockBiasSeconds() - b.getReceiverClockBiasSeconds()));
        }
        // Repeatability guards for this fixed synthetic RF fixture, not accuracy PASS thresholds.
        assertTrue(position < 1.0, name + " position delta: " + position);
        assertTrue(velocity < 0.1, name + " velocity delta: " + velocity);
        assertTrue(clock < 1e-8, name + " clock delta: " + clock);
        return Map.of("name", name, "epochs", actual.size(), "maxPositionDeltaMeters", position,
                "maxVelocityDeltaMetersPerSecond", velocity, "maxClockDeltaSeconds", clock);
    }

    private double distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < 3; i++) sum += (a[i] - b[i]) * (a[i] - b[i]);
        return Math.sqrt(sum);
    }
}
