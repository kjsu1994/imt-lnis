package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import server.shared.model.DtnModels.IqMetadata;

/** Uses the fixed 90-second RF fixture replayed at 20x, 1x, and 20x. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "LNIS_IQ_REPLAY_DIRECTORY", matches = ".+")
class NativeFileReplayIntegrationTest {
    @Test
    void completedReplaysReachTheLastEpochWithoutJsonNavigation() throws Exception {
        Path root = Path.of(System.getenv("LNIS_IQ_REPLAY_DIRECTORY"));
        var json = new ObjectMapper().findAndRegisterModules();
        var source = json.readValue(root.resolve("source.json").toFile(), IqReceiver.Source.class);
        var metadata = source.metadata();
        var frameOnly = new IqMetadata(metadata.signal(), metadata.pvtMethod(), metadata.week(),
                metadata.towSeconds(), metadata.trajectory(), metadata.prns(), List.of());
        var receiver = new IqReceiver("unused", System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
        var reports = new ArrayList<Map<String, Object>>();

        for (String file : List.of("tracking-0-20.log", "tracking-1-1.log", "tracking-2-20.log")) {
            var result = receiver.calculate(root.resolve(file), frameOnly);
            assertEquals(69, result.pvt().size(), file);
            for (int i = 0; i < result.pvt().size(); i++) {
                var pvt = result.pvt().get(i);
                assertEquals(metadata.week(), pvt.getWeek(), file);
                assertEquals(metadata.towSeconds() + 21 + i, pvt.getTowSeconds(), file);
                assertTrue(pvt.isPositionValid(), file + " position at epoch " + i);
                assertTrue(pvt.isVelocityValid(), file + " velocity at epoch " + i);
            }
            var reference = IqReceiver.references(metadata, List.of(source.reference()), result.pvt());
            reports.add(Map.of("trace", file, "comparison", IqReceiver.comparison(reference, result.pvt())));
        }
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("replay-pvt.json").toFile(), reports);
    }
}
