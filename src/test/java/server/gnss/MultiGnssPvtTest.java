package server.gnss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import server.afs.NativeAfsCodec;
import server.common.DtnModels;
import server.dtn.DtnProcessor;
import server.pvt.NativePvtCodec;
import server.pvt.PvtConstellation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * 실제 수신기 원시 데이터(UBX, GRAW)를 사용해 Multi-GNSS PVT 계산 및 위성군별 동작을 검증한다.
 */
@EnabledOnOs(OS.WINDOWS)
public class MultiGnssPvtTest {
    private Path candidate() {
        return Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
    }

    @Test
    void realGnss10EpochsGrawSolvesGpsAndAll() throws Exception {
        byte[] grawBytes = Files.readAllBytes(Path.of("real-gnss-10epochs.graw"));
        var records = GrawCodec.splitLengthPrefixed(grawBytes);

        try (var pvt = new NativePvtCodec(candidate())) {
            var gpsSolutions = pvt.calculate(records, PvtConstellation.GPS);
            assertEquals(10, gpsSolutions.size());
            assertEquals(10, gpsSolutions.stream().filter(DtnModels.Pvt::isPositionValid).count(),
                    "GPS 단독으로 10개 에폭 모두 유효한 3D PVT 해가 산출되어야 한다.");

            var allSolutions = pvt.calculate(records, PvtConstellation.ALL);
            assertEquals(10, allSolutions.size());
            long allValid = allSolutions.stream().filter(DtnModels.Pvt::isPositionValid).count();
            assertTrue(allValid >= 8, "Multi-GNSS로 대다수 에폭이 유효해야 한다: " + allValid);

            // 동일 안테나 위치이므로 GPS 해와 Multi-GNSS 해의 좌표 편차가 일정 범위 이내여야 한다.
            var firstGps = gpsSolutions.getFirst().getEcefMeters();
            var firstAll = allSolutions.getFirst().getEcefMeters();
            assertNotNull(firstGps);
            assertNotNull(firstAll);
            double dx = firstGps[0] - firstAll[0];
            double dy = firstGps[1] - firstAll[1];
            double dz = firstGps[2] - firstAll[2];
            double diff = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertTrue(diff < 20.0, "GPS와 Multi-GNSS의 ECEF 위치 차이는 20m 이내여야 함 (실제: " + diff + "m)");
        }
    }

    @Test
    void realGnssSourceUbxMultiGnssAvailabilityImprovement() throws Exception {
        byte[] ubxBytes = Files.readAllBytes(Path.of("real-gnss-source.ubx"));
        byte[] grawBytes = UbxGrawImport.convertAll(ubxBytes, Instant.EPOCH, "real-gnss-source.ubx");
        var records = GrawCodec.splitLengthPrefixed(grawBytes);

        try (var pvt = new NativePvtCodec(candidate())) {
            var gpsSolutions = pvt.calculate(records, PvtConstellation.GPS);
            long gpsValidCount = gpsSolutions.stream().filter(DtnModels.Pvt::isPositionValid).count();
            assertEquals(24, gpsSolutions.size());
            assertEquals(10, gpsValidCount, "23초 UBX 데이터에서 GPS 단독 유효 에폭은 10개이다.");

            var allSolutions = pvt.calculate(records, PvtConstellation.ALL);
            long allValidCount = allSolutions.stream().filter(DtnModels.Pvt::isPositionValid).count();
            assertEquals(24, allSolutions.size());
            assertTrue(allValidCount >= 20, 
                    "Multi-GNSS 결합 시 가용 에폭이 20개 이상으로 대폭 향상되어야 함 (실제: " + allValidCount + ")");

            // BeiDou 및 Galileo 단독 모드는 관측 데이터를 독립적으로 필터링해야 함
            var bdsSolutions = pvt.calculate(records, PvtConstellation.BEIDOU);
            assertEquals(24, bdsSolutions.size());
            // 23초 구간 내 BDS subframe 3 미수신으로 인해 항법 궤도 미완성 상태가 정상 감지되어야 함
            assertTrue(bdsSolutions.stream().noneMatch(DtnModels.Pvt::isPositionValid));

            var galSolutions = pvt.calculate(records, PvtConstellation.GALILEO);
            assertEquals(24, galSolutions.size());
            assertTrue(galSolutions.stream().noneMatch(DtnModels.Pvt::isPositionValid));
        }
    }

    @Test
    void dtnProcessorRoundTripWithMultiGnss() throws Exception {
        try (var codec = NativeAfsCodec.load(candidate())) {
            var processor = new DtnProcessor(codec, candidate());
            byte[] grawBytes = Files.readAllBytes(Path.of("real-gnss-10epochs.graw"));
            UUID id = UUID.randomUUID();

            // Multi-GNSS (ALL) 설정으로 시험 준비 및 전송 검증
            var prepared = processor.prepare(id, grawBytes, true, PvtConstellation.ALL);
            assertEquals("ALL", prepared.getTransfer().getPvtConstellation());


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
