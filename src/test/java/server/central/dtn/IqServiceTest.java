package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.shared.model.DtnModels.*;

class IqServiceTest {
  @TempDir Path directory;
  private IqService service() { return new IqService(new ObjectMapper(), directory.toString(), directory.resolve("missing").toString(), false); }
  private IqFile file(String path) { return new IqFile(path, IqService.EXPECTED_BYTES, "A".repeat(64), 90, 12_000_000, "IQ_INTERLEAVED_INT8", 2); }
  @Test void rejectsTraversalMissingAndIncompleteFiles() throws Exception {
    var service = service();
    assertFalse(service.enabled()); assertThrows(IllegalStateException.class, service::start);
    assertThrows(IllegalArgumentException.class, () -> service.path(file("/exchange/../DB/lnis.mv.db")));
    UUID id = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () -> service.path(file("/exchange/" + id + ".bin")));
    Files.write(directory.resolve(id + ".bin"), new byte[]{1, -1, 3, -3});
    assertThrows(IllegalArgumentException.class, () -> service.verify(file("/exchange/" + id + ".bin")));
    assertEquals(java.util.List.of(java.util.List.of(1,-1),java.util.List.of(3,-3)), service.preview(file("/exchange/" + id + ".bin")));
    assertThrows(IllegalArgumentException.class, () -> service.completed(id));
  }
  @Test void iqContractContainsOnlyFileReferenceAndDispatchFields() throws Exception {
    Transfer request = new Transfer(); request.setTestId(UUID.randomUUID()); request.setTestType("IQ_SAMPLE");
    request.setSenderMode("HDTN"); request.setReceiverMode("DTN"); request.setFile(file("/exchange/" + UUID.randomUUID() + ".bin"));
    var json = new ObjectMapper().valueToTree(request);
    assertEquals("IQ_SAMPLE", json.path("testType").asText());
    assertEquals("HDTN", json.path("senderMode").asText()); assertEquals("DTN", json.path("receiverMode").asText());
    assertEquals(2160000000L, json.path("file").path("sizeBytes").asLong());
    assertTrue(json.path("grawBase64").isNull()); assertTrue(json.path("frames").isNull());
  }
  @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void earthInputRequiresMatchingValidEpochAndKeepsObservedPrns() throws Exception {
    var records = server.shared.codec.GrawCodec.splitLengthPrefixed(server.agent.codec.NativePvtIntegrationTest.validSample());
    try (var calculator = new server.agent.codec.NativePvtCodec(Path.of("native/bin/win-x64"))) {
      var pvt = calculator.calculate(records).getFirst();
      String input = IqService.earthInput(records, pvt);
      assertTrue(input.startsWith("LNIS-IQ-EARTH-1 2400 100000.0 "));
      assertEquals(java.util.List.of("P 19", "P 23", "P 24", "P 28", "P 29"),
          input.lines().filter(line -> line.startsWith("P ")).toList());
      assertEquals(96, input.lines().filter(line -> line.startsWith("N ")).count());
      pvt.setTowSeconds(100001);
      assertThrows(IllegalArgumentException.class, () -> IqService.earthInput(records, pvt));
      pvt.setPositionValid(false);
      assertThrows(IllegalArgumentException.class, () -> IqService.earthInput(records, pvt));
      assertThrows(IllegalArgumentException.class, () -> service().start("invalid"));
    }
  }
  @Test void deletionIsLimitedToSelectedFilesAndMetadataSurvivesRestart() throws Exception {
    var service = service(); UUID id = UUID.randomUUID(); UUID other = UUID.randomUUID();
    Files.write(directory.resolve(id + ".bin"), new byte[]{1,-1});
    Files.write(directory.resolve(other + ".bin"), new byte[]{3,-3});
    new ObjectMapper().writeValue(directory.resolve(id + ".json").toFile(), file("/exchange/" + id + ".bin"));
    assertEquals(id, service.recent().getFirst().get("id"));
    service.delete(id);
    assertFalse(Files.exists(directory.resolve(id + ".bin")));
    assertFalse(Files.exists(directory.resolve(id + ".json")));
    assertTrue(Files.exists(directory.resolve(other + ".bin")));
  }
}
