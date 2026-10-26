package server.iq;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import server.afs.NativePvtIntegrationTest;
import server.common.DtnModels.Pvt;
import server.gnss.GrawCodec;
import server.gnss.UbxGrawImport;
import server.pvt.NativePvtCodec;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class IqServiceTest {
    @TempDir Path directory;

    @Test
    void excludesObservedSatelliteWithoutNavigationFromAllGeneratorRecords() throws Exception {
        var records = new ArrayList<>(GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample())
                .stream().filter(bytes -> !(GrawCodec.decode(bytes).message()
                        instanceof GrawCodec.NavigationUpdate nav) || nav.satelliteId() != 26).toList());
        var record = GrawCodec.decode(records.getLast());
        var epoch = (GrawCodec.ObservationEpoch) record.message();
        var observations = new ArrayList<>(epoch.observations());
        observations.add(new GrawCodec.Observation(
                22000000, 0, -1000, 0, 26, 0, 0, 1000, 45, 1, 1, 1, 1));
        records.set(records.size() - 1, GrawCodec.encode(new GrawCodec.Envelope(
                record.testId(), record.messageId(), record.sequence(), record.capturedAt(),
                new GrawCodec.ObservationEpoch(epoch.receiverTowSeconds(), epoch.week(),
                        epoch.leapSeconds(), epoch.receiverStatus(), epoch.rawxVersion(), observations))));
        var messages = new ArrayList<String>();
        String input = IqService.earthInput(records, reference(2400, 100000), messages::add);
        var source = IqReceiver.source(input, "A".repeat(64));
        assertEquals(List.of(19, 23, 24, 28, 29), source.metadata().prns());
        assertEquals(15, source.metadata().gpsLnav().size());
        assertFalse(input.lines().anyMatch(l -> l.matches("[NPF] 26(?: .*|$)")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("제외 PRN [26]")));
    }

    @Test
    void rejectsFewerThanFourReadySatellitesBeforeStartingGenerator() throws Exception {
        var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()).stream()
                .filter(bytes -> !(GrawCodec.decode(bytes).message() instanceof GrawCodec.NavigationUpdate nav)
                        || (nav.satelliteId() != 28 && nav.satelliteId() != 29))
                .toList();
        var error = assertThrows(IllegalArgumentException.class,
                () -> IqService.earthInput(records, reference(2400, 100000)));
        assertTrue(error.getMessage().contains("위성 3개 · 최소 4개 필요"));
    }

    @Test
    void reportsNativeFailureWithoutRequiringPartialOutputAndBoundsLogTail() throws Exception {
        Path log = directory.resolve("generation.log");
        Files.writeString(log, "x".repeat(8000) + "\nERROR: missing GPS LNAV\n");
        String message = IqService.generationFailure(2, 0, log);
        assertTrue(message.contains("종료 코드 2"));
        assertTrue(message.contains("출력 0 / " + IqService.EXPECTED_BYTES));
        assertTrue(message.contains("ERROR: missing GPS LNAV"));
        assertTrue(message.length() < 4300);
        assertTrue(IqService.generationFailure(2, 0, directory.resolve("absent.log"))
                .contains("종료 코드 2"));
        assertTrue(IqService.generationFailure(0, 123, log).contains("출력 123 /"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void realFailedUbxInputSelectsSixNavigationReadyPrns() throws Exception {
        var records = GrawCodec.splitLengthPrefixed(UbxGrawImport.convertAll(
                Files.readAllBytes(Path.of("test2.ubx")), Instant.EPOCH, "test2.ubx"));
        try (var solver = new NativePvtCodec(Path.of(
                System.getProperty("lnis.native.candidate", "native/bin/win-x64")))) {
            var reference = solver.calculate(records).stream()
                    .filter(p -> p.isPositionValid() && p.isVelocityValid()).findFirst().orElseThrow();
            var messages = new ArrayList<String>();
            String input = IqService.earthInput(records, reference, messages::add);
            var source = IqReceiver.source(input, "A".repeat(64));
            assertEquals(Set.of(3, 4, 16, 28, 29, 31), new HashSet<>(source.metadata().prns()));
            assertEquals(18, source.metadata().gpsLnav().size());
            assertTrue(messages.stream().anyMatch(m -> m.contains("제외 PRN [26]")));
            Path report = Path.of("build/reports/iq-missing-navigation");
            Files.createDirectories(report);
            Files.writeString(report.resolve("earth-input.txt"), input);
        }
    }

    private static Pvt reference(int week, double tow) {
        var pvt = new Pvt();
        pvt.setWeek(week);
        pvt.setTowSeconds(tow);
        pvt.setPositionValid(true);
        pvt.setVelocityValid(true);
        pvt.setEcefMeters(new double[] {-3049086, 4046274, 3861625});
        pvt.setVelocityMetersPerSecond(new double[] {0, 0, 0});
        pvt.setReceiverClockBiasSeconds(0.0);
        return pvt;
    }
}
