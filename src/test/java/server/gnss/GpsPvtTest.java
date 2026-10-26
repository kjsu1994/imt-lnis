package server.gnss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import server.afs.NativeAfsCodec;
import server.common.DtnModels;
import server.dtn.DtnProcessor;
import server.pvt.NativePvtCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** GPS PVT regression with real receiver input. */
@EnabledOnOs(OS.WINDOWS)
public class GpsPvtTest {
    private Path candidate() {
        return Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
    }

    @Test
    void realGnss10EpochsGrawSolvesGps() throws Exception {
        byte[] grawBytes = Files.readAllBytes(Path.of("real-gnss-10epochs.graw"));
        var records = GrawCodec.splitLengthPrefixed(grawBytes);

        try (var pvt = new NativePvtCodec(candidate())) {
            var gpsSolutions = pvt.calculate(records);
            assertEquals(10, gpsSolutions.size());
            assertEquals(10, gpsSolutions.stream().filter(DtnModels.Pvt::isPositionValid).count(),
                    "GPS 단독으로 10개 에폭 모두 유효한 3D PVT 해가 산출되어야 한다.");


        }
    }

    @Test
    void realUbxRetainsGpsAvailability() throws Exception {
        byte[] ubxBytes = Files.readAllBytes(Path.of("test2.ubx"));
        byte[] grawBytes = UbxGrawImport.convertAll(ubxBytes, Instant.EPOCH, "test2.ubx");
        var records = GrawCodec.splitLengthPrefixed(grawBytes);

        try (var pvt = new NativePvtCodec(candidate())) {
            var gpsSolutions = pvt.calculate(records);
            long gpsValidCount = gpsSolutions.stream().filter(DtnModels.Pvt::isPositionValid).count();
            assertEquals(24, gpsSolutions.size());
            assertEquals(10, gpsValidCount, "23초 UBX 데이터에서 GPS 단독 유효 에폭은 10개이다.");


        }
    }

    @Test
    void dtnProcessorGpsRoundTrip() throws Exception {
        try (var codec = NativeAfsCodec.load(candidate())) {
            var processor = new DtnProcessor(codec, candidate());
            byte[] grawBytes = Files.readAllBytes(Path.of("real-gnss-10epochs.graw"));
            UUID id = UUID.randomUUID();

            var prepared = processor.prepare(id, grawBytes, true);
            assertEquals("GPS", prepared.getTransfer().getPvtConstellation());


            var received = processor.receive(id, prepared.getTransfer());
            assertEquals(prepared.getPvt().size(), received.getPvt().size());
            for (int i = 0; i < prepared.getPvt().size(); i++) {
                assertEquals(prepared.getPvt().get(i).isPositionValid(), 
                        received.getPvt().get(i).isPositionValid());
                if (prepared.getPvt().get(i).isPositionValid()) {
                    assertArrayEquals(prepared.getPvt().get(i).getEcefMeters(), 
                            received.getPvt().get(i).getEcefMeters(), 1e-6);
                }
            }
        }
    }
}
