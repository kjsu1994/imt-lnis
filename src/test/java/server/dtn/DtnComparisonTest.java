package server.dtn;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import server.common.DtnModels.Pvt;
import server.pvt.DtnComparison;

import java.util.List;

/** 전달 성공과 PVT 계산 성공을 구분하고 위치/속도/시각 오류를 검출한다. */
class DtnComparisonTest {
    @Test
    void comparesSameEpochAndDetectsDifferences() {
        var a = solution();
        var b = solution();
        assertEquals("PASS", DtnComparison.compare(List.of(a), List.of(b)).get("verdict"));
        b.setEcefMeters(new double[] {1, 2, 4});
        assertEquals("FAIL", DtnComparison.compare(List.of(a), List.of(b)).get("verdict"));
        b.setTowSeconds(2);
        assertThrows(
                IllegalArgumentException.class,
                () -> DtnComparison.compare(List.of(a), List.of(b)));
    }

    @Test
    void noSolutionOrMissingVelocityIsNotPass() {
        var empty = new Pvt();
        assertEquals(
                "INCONCLUSIVE",
                DtnComparison.compare(List.of(empty), List.of(empty)).get("verdict"));
        var a = solution();
        a.setVelocityValid(false);
        a.setVelocityMetersPerSecond(null);
        assertEquals("INCONCLUSIVE", DtnComparison.compare(List.of(a), List.of(a)).get("verdict"));
    }

    static Pvt solution() {
        Pvt p = new Pvt();
        p.setWeek(2400);
        p.setTowSeconds(1);
        p.setPositionValid(true);
        p.setVelocityValid(true);
        p.setEcefMeters(new double[] {1, 2, 3});
        p.setVelocityMetersPerSecond(new double[] {0, 0, 0});
        p.setReceiverClockBiasSeconds(1e-6);
        p.setSatellitesUsed(6);
        return p;
    }
}
