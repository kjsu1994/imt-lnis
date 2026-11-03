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
        assertFalse(input.lines().anyMatch(l -> l.matches("[NPOF] 26(?: .*|$)")));
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
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValue(report.resolve("source.json").toFile(), source);
        }
    }

    @Test
    void dispatchReusesGenerationDigestButStillChecksPathSizeAndFormat() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var service = new IqService(json, directory.toString(), "unused", false);
        UUID id = UUID.randomUUID();
        Path bin = directory.resolve(id + ".bin");
        Files.write(bin, new byte[] {1, -1, 3, -3});
        var file = new server.common.DtnModels.IqFile("/exchange/" + id + ".bin",
                IqService.EXPECTED_BYTES, "A".repeat(64), 90, 12000000, "IQ_INTERLEAVED_INT8", 2);
        json.writeValue(directory.resolve(id + ".json").toFile(), file);
        json.writeValue(directory.resolve(id + ".context.json").toFile(),
                Map.of("metadata", Map.of("pvtMethod", IqReceiver.RF_METHOD)));
        try (var files = org.mockito.Mockito.mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(() -> Files.size(bin)).thenReturn(IqService.EXPECTED_BYTES);
            assertEquals(file, service.completed(id), "dispatch must not recalculate the digest");
            var error = assertThrows(java.io.IOException.class, () -> service.verify(file));
            assertTrue(error.getMessage().contains("검증 중 파일 크기 변경"),
                    "receiver verification must still read all file bytes");
            files.when(() -> Files.size(bin)).thenReturn(4L);
            assertThrows(IllegalArgumentException.class, () -> service.completed(id));
            json.writeValue(directory.resolve(id + ".context.json").toFile(),
                    Map.of("metadata", Map.of("pvtMethod", IqReceiver.FRAME_METHOD)));
            assertEquals("REGENERATE", service.status(id).get("state"));
            assertTrue(Files.exists(bin), "old I/Q files must be preserved");
        }
        Files.delete(bin);
        assertThrows(IllegalStateException.class, () -> service.completed(id));
        assertThrows(IllegalArgumentException.class, () -> service.path(
                new server.common.DtnModels.IqFile("/exchange/../secret", file.sizeBytes(),
                        file.sha256(), 90, 12000000, "IQ_INTERLEAVED_INT8", 2)));
    }

    @Test
    void hashReportsProgressAndMatchesDigestWithoutChangingReadSize() throws Exception {
        Path file = directory.resolve("checksum.bin");
        byte[] data = new byte[1024 * 1024 + 7];
        new Random(26).nextBytes(data);
        Files.write(file, data);
        var progress = new ArrayList<IqService.VerificationProgress>();
        String actual = IqService.sha256(file, data.length, progress::add);
        assertEquals(server.common.Hashing.hex(
                java.security.MessageDigest.getInstance("SHA-256").digest(data)), actual);
        assertEquals(0, progress.getFirst().checkedBytes());
        assertEquals(data.length, progress.getLast().checkedBytes());
        assertTrue(progress.getLast().message().contains("100%"));
        assertTrue(progress.getLast().message().contains("MB/s"));
        assertThrows(java.io.IOException.class,
                () -> IqService.sha256(file, data.length + 1, value -> {}));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> IqService.sha256(file, data.length, value -> {
                    throw new java.util.concurrent.CancellationException("cancelled");
                }));
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.IOException.class,
                    () -> IqService.sha256(file, data.length, value -> {}));
        } finally {
            Thread.interrupted();
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
    @Test
    void generationKeepsOneOriginalEpochAndReadsOldContextsWithoutInventingData() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var service = new IqService(json, directory.toString(), "unused", false);
        var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
        var reference = reference(2400, 100000);
        String raw = Base64.getEncoder().encodeToString(IqService.selectedInput(records, reference));
        var original = server.common.DtnObservationView.fromRecords(GrawCodec.splitLengthPrefixed(Base64.getDecoder().decode(raw)));
        assertEquals(1, original.epochs().size());
        assertFalse(original.navigation().isEmpty());
        var generated = IqReceiver.source(IqService.earthInput(records, reference), "A".repeat(64));
        var snapshot = new IqReceiver.Source(generated.sha256(), generated.metadata(), reference, raw);
        UUID id = UUID.randomUUID();
        var file = new server.common.DtnModels.IqFile("/exchange/" + id + ".bin", IqService.EXPECTED_BYTES,
                generated.sha256(), 90, 12000000, "IQ_INTERLEAVED_INT8", 2);
        Path context = directory.resolve(id + ".context.json");
        json.writeValue(context.toFile(), snapshot);
        var restored = service.source(id, file);
        assertEquals(raw, restored.grawBase64());
        assertEquals(json.valueToTree(reference), json.valueToTree(restored.reference()));
        assertEquals(raw, new IqService(json, directory.toString(), "unused", false).source(id, file).grawBase64());
        var m = generated.metadata();
        var otherEpoch = new server.common.DtnModels.IqMetadata(m.signal(), m.pvtMethod(), m.week()+1,
                m.towSeconds(), m.trajectory(), m.prns(), m.gpsLnav());
        json.writeValue(context.toFile(), new IqReceiver.Source(generated.sha256(), otherEpoch, reference, raw));
        assertThrows(java.io.IOException.class, () -> service.source(id, file));
        json.writeValue(context.toFile(), new IqReceiver.Source(generated.sha256(), m, reference));
        assertNull(service.source(id, file).grawBase64());
    }

    @Test
    void controllerPassesTheActualReferenceAndSelectedOriginalToGeneration() throws Exception {
        var iq = org.mockito.Mockito.mock(IqService.class);
        var inputs = org.mockito.Mockito.mock(server.gnss.InputBufferService.class);
        var calculator = org.mockito.Mockito.mock(server.pvt.DtnPvtCalculator.class);
        var controller = new IqController(iq, org.mockito.Mockito.mock(server.dtn.DtnService.class), inputs);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "calculator", calculator);
        UUID id = UUID.randomUUID();
        byte[] bytes = NativePvtIntegrationTest.validSample();
        var input = org.mockito.Mockito.mock(server.gnss.InputBufferEntity.class);
        org.mockito.Mockito.when(inputs.get(id)).thenReturn(input);
        org.mockito.Mockito.when(input.complete()).thenReturn(true);
        org.mockito.Mockito.when(input.receivedSize()).thenReturn((long) bytes.length);
        org.mockito.Mockito.when(inputs.readChunks(input, server.common.DtnModels.MAX_INPUT_BYTES)).thenReturn(bytes);
        var reference = reference(2400, 100000);
        reference.setSatellitesUsed(4);
        org.mockito.Mockito.when(calculator.calculate(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(reference));
        controller.start(new IqController.GenerateRequest(id));
        String raw = Base64.getEncoder().encodeToString(IqService.selectedInput(GrawCodec.splitLengthPrefixed(bytes), reference));
        org.mockito.Mockito.verify(iq).start(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq(raw), org.mockito.ArgumentMatchers.same(reference));
    }

}
