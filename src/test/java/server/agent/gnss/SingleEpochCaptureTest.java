package server.agent.gnss;
import server.shared.codec.SingleEpochCapture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import server.shared.codec.NativePvtCodec;
import server.agent.codec.NativePvtIntegrationTest;
import server.shared.codec.GrawCodec;

class SingleEpochCaptureTest {
  @Test void dropsUnsolvableEpochsAndKeepsNavigationOrder() throws Exception {
    var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
    var epoch = records.stream().filter(r -> GrawCodec.decode(r).message() instanceof GrawCodec.ObservationEpoch).findFirst().orElseThrow();
    var capture = new SingleEpochCapture(candidate -> candidate.size() > 1);
    assertNull(capture.accept(epoch));
    List<byte[]> selected = null;
    for (byte[] record : records) { selected = capture.accept(record); if (selected != null) break; }
    assertNotNull(selected);
    assertEquals(1, selected.stream().filter(r -> GrawCodec.decode(r).message() instanceof GrawCodec.ObservationEpoch).count());
  }

  @Test void selectsAnEpochWithTheExistingEarthEngine() throws Exception {
    Path directory = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
    var capture = new SingleEpochCapture(candidate -> {
      try (var codec = new NativePvtCodec(directory)) {
        var pvt = codec.calculate(candidate).getFirst();
        return pvt.isPositionValid() && pvt.isVelocityValid();
      }
    });
    var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
    var observation = records.stream().filter(r -> GrawCodec.decode(r).message() instanceof GrawCodec.ObservationEpoch).findFirst().orElseThrow();
    assertNull(capture.accept(observation), "An epoch without navigation must not finish capture");
    List<byte[]> selected = null;
    for (byte[] record : records) { selected = capture.accept(record); if (selected != null) break; }
    assertNotNull(selected);
    try (var codec = new NativePvtCodec(directory)) {
      var pvt = codec.calculate(selected).getFirst();
      assertTrue(pvt.isPositionValid());
      assertTrue(pvt.isVelocityValid());
    }
  }

  @Test void boundsNavigationMemory() throws Exception {
    var record = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()).stream()
        .filter(r -> !(GrawCodec.decode(r).message() instanceof GrawCodec.ObservationEpoch)).findFirst().orElseThrow();
    var capture = new SingleEpochCapture(candidate -> false);
    assertThrows(IllegalStateException.class, () -> { for (int i = 0; i < 100000; i++) capture.accept(record); });
  }
}
