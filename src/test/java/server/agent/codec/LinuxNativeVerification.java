package server.agent.codec;

import server.agent.dtn.DtnProcessor;
import server.shared.model.DtnModels.AgentResult;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Linux 컨테이너에서 실제 JNA 경계와 기존 합성 GRAW/PVT 회귀 입력을 검증한다. */
public final class LinuxNativeVerification {
    private LinuxNativeVerification()
    {
    }

    public static void main(String[] arguments) throws Exception
    {
        assertEquals("Linux", System.getProperty("os.name"));
        Path directory = Path.of(System.getProperty("lnis.native.candidate"));
        try (NativeAfsCodec codec = NativeAfsCodec.load(directory)) {
            assertEquals(1, codec.abiVersion());
            DtnProcessor processor = new DtnProcessor(codec, directory);
            UUID id = UUID.randomUUID();
            AgentResult prepared = processor.prepare(id, NativePvtIntegrationTest.sample());
            AgentResult received = processor.receive(id, prepared.getTransfer());
            assertEquals(prepared.getPvt(), received.getPvt());
            assertFalse(received.getPvt().getFirst().isPositionValid());
            prepared.getTransfer().setSourceSha256("0".repeat(64));
            assertThrows(IllegalArgumentException.class,
                    () -> processor.receive(id, prepared.getTransfer()));
        }
        NativePvtIntegrationTest regression = new NativePvtIntegrationTest();
        regression.rejectsMalformedNativeInputWithoutFabricatingCoordinates();
        regression.reservedSignalByteDoesNotTreatCnavAsLnav();
        regression.validGpsSolutionSurvivesAfsRoundTrip();
        regression.rawUsesSameEarthSolverAndRejectsDispatchOrHashChanges();
        System.out.println("PASS: Linux JNA ABI, AFS/DTN roundtrip, PVT solution, no-fix, CNAV exclusion, hash rejection");
    }
}
