package server.agent.dtn;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import server.agent.codec.NativeAfsCodec;
import server.agent.codec.NativePvtIntegrationTest;
import server.shared.codec.GrawCodec;
import server.shared.codec.GrawCodec.*;
import server.shared.codec.Hashing;
import server.shared.model.DtnModels.*;

@EnabledOnOs(OS.WINDOWS)
class AfsMetadataCodecTest {
  private final Path directory = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  private Transfer prepare(List<byte[]> records, NativeAfsCodec codec) {
    var transfer = new Transfer(); transfer.setTestId(UUID.randomUUID()); transfer.setRecordCount(records.size());
    var digest = Hashing.sha256Digest();
    for (byte[] bytes : records) {
      digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    transfer.setSourceSha256(Hashing.hex(digest.digest()));
    AfsMetadataCodec.prepare(transfer, records, codec);
    return transfer;
  }
  private Transfer copy(Transfer t) throws Exception { return mapper.readValue(mapper.writeValueAsBytes(t), Transfer.class); }

  @Test void exactSourceSurvivesResidualBitsRepeatedFramesAndOtherMessages() throws Exception {
    var records = new ArrayList<>(GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()));
    // Ensure e's 2^-33 residual bit and UBX parity/top bits cannot silently disappear.
    Envelope second = GrawCodec.decode(records.get(1));
    NavigationUpdate nav = (NavigationUpdate)second.message();
    var words = new ArrayList<>(nav.words());
    words.set(6, words.get(6) | (1L << 6) | 0xc000003fL); // LNAV bit 167 = e LSB.
    records.set(1, GrawCodec.encode(new Envelope(second.testId(),second.messageId(),second.sequence(),second.capturedAt(),
        new NavigationUpdate(0,nav.satelliteId(),0,0,nav.sfrbxVersion(),words))));
    records.add(records.get(2)); // Reuse sf1/sf2 with another sf3 record.
    records.add(GrawCodec.encode(new Envelope(second.testId(),UUID.randomUUID(),98,second.capturedAt(),
        new NavigationUpdate(6,4,0,0,2,List.of(0xffffffffL,1L,2L)))));
    records.add(GrawCodec.encode(new Envelope(second.testId(),UUID.randomUUID(),99,second.capturedAt(),
        new ReceiverMetadata("EVK-F9T","test","COM3",115200,"test"))));
    // A second epoch, reversed satellite order, another signal and a non-GPS observation.
    var epoch = (ObservationEpoch)GrawCodec.decode(records.get(96)).message();
    var observations = new ArrayList<>(epoch.observations());
    Collections.reverse(observations);
    observations.add(new Observation(22000000,12,-10,0,19,3,0,1000,40,1,1,1,1));
    observations.add(new Observation(38000000,14,-20,1,133,0,0,1000,40,1,1,1,1));
    records.add(GrawCodec.encode(new Envelope(second.testId(),UUID.randomUUID(),100,second.capturedAt(),
        new ObservationEpoch(100001,2400,18,1,1,observations))));
    try (var codec = NativeAfsCodec.load(directory)) {
      Transfer transfer = copy(prepare(records,codec));
      var metadata = (AfsMetadata)transfer.getMetadata();
      assertEquals(1L, (metadata.records().get(1).navigation().words().get(6) >>> 6) & 1);
      assertNotEquals(nav.words(), metadata.records().get(1).navigation().words());
      List<byte[]> restored = AfsMetadataCodec.restore(transfer,codec);
      assertEquals(records.size(), restored.size());
      for (int i = 0; i < records.size(); i++) assertArrayEquals(records.get(i), restored.get(i), "record " + i);
      AfsMetadataCodec.group(transfer);
      transfer = copy(transfer);
      assertNull(transfer.getFrames());
      assertEquals(3,transfer.getSchemaVersion());
      restored = AfsMetadataCodec.restore(transfer,codec);
      for (int i = 0; i < records.size(); i++) assertArrayEquals(records.get(i),restored.get(i),"grouped record " + i);
    }
  }

  @Test void groupedMetadataRejectsDuplicateMissingAndMismatchedMeasurements() throws Exception {
    try (var codec = NativeAfsCodec.load(directory)) {
      var original = prepare(GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()),codec);
      AfsMetadataCodec.group(original);
      var gps19 = original.getSatellites().stream().filter(s -> s.prn() == 19).findFirst().orElseThrow();
      assertEquals(1,gps19.frames().size());
      assertEquals(3,gps19.metadata().navigationSupplement().size());
      assertEquals(1,gps19.metadata().observations().size());
      assertEquals(20453375.918,gps19.metadata().observations().getFirst().observation().pseudorangeMeters(),0.001);
      Transfer duplicate = copy(original);
      var measurements = duplicate.getSatellites().get(18).metadata().observations();
      measurements.add(measurements.getFirst());
      assertThrows(IllegalArgumentException.class,() -> AfsMetadataCodec.restore(duplicate,codec));
      Transfer missing = copy(original); missing.getSatellites().get(18).metadata().observations().clear();
      assertThrows(IllegalArgumentException.class,() -> AfsMetadataCodec.restore(missing,codec));
      Transfer mismatched = copy(original);
      var items = mismatched.getSatellites().get(18).metadata().observations();
      var item = items.getFirst();
      items.set(0,new AfsMeasurement(item.recordIndex(),item.measurementIndex(),item.week(),item.towSeconds()+1,item.observation()));
      assertThrows(IllegalArgumentException.class,() -> AfsMetadataCodec.restore(mismatched,codec));
      Transfer nav = copy(original);
      nav.getSatellites().getFirst().metadata().navigationSupplement().clear();
      assertThrows(IllegalArgumentException.class,() -> AfsMetadataCodec.restore(nav,codec));
      assertDoesNotThrow(() -> AfsMetadataCodec.restore(original,codec));
    }
  }

  @Test void rejectsMissingFramesBadReferencesAndNonPatternDataEvenWithValidCrc() throws Exception {
    try (var codec = NativeAfsCodec.load(directory)) {
      Transfer original = prepare(GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()),codec);
      Transfer missing = copy(original); missing.setFrames(List.of());
      assertThrows(IllegalArgumentException.class, () -> AfsMetadataCodec.restore(missing,codec));
      Transfer wrong = copy(original); wrong.getFrames().getFirst().setPrn(32);
      assertThrows(IllegalArgumentException.class, () -> AfsMetadataCodec.restore(wrong,codec));
      Transfer reference = copy(original); reference.getFrames().getFirst().setNavigationRecordIndices(List.of(0,1,96));
      assertThrows(IllegalArgumentException.class, () -> AfsMetadataCodec.restore(reference,codec));
      for (int block : List.of(2,3,4)) {
        Transfer altered = copy(original); Frame frame = altered.getFrames().getFirst();
        var decoded = codec.decode(frame.getToi(), Base64.getDecoder().decode(frame.getFrameBase64()));
        byte[] bits = block == 2 ? decoded.sb2() : block == 3 ? decoded.sb3() : decoded.sb4();
        bits[300] ^= 1;
        frame.setFrameBase64(Base64.getEncoder().encodeToString(codec.encode(frame.getToi(),decoded.sb2(),decoded.sb3(),decoded.sb4())));
        assertThrows(IllegalArgumentException.class, () -> AfsMetadataCodec.restore(altered,codec));
      }
      Transfer changed = copy(original); Frame frame = changed.getFrames().getFirst();
      var decoded = codec.decode(frame.getToi(), Base64.getDecoder().decode(frame.getFrameBase64()));
      decoded.sb2()[250] ^= 1; // Valid CRC, changed af0 must fail original-source hash.
      frame.setFrameBase64(Base64.getEncoder().encodeToString(codec.encode(frame.getToi(),decoded.sb2(),decoded.sb3(),decoded.sb4())));
      assertThrows(IllegalArgumentException.class, () -> AfsMetadataCodec.restore(changed,codec));
      assertDoesNotThrow(() -> AfsMetadataCodec.restore(original,codec));
    }
  }

  @Test void legacyAfsReceiveStillWorks() {
    byte[] source = NativePvtIntegrationTest.sample();
    var records = GrawCodec.splitLengthPrefixed(source);
    try (var codec = NativeAfsCodec.load(directory)) {
      Transfer transfer;
      try (var fixture = getClass().getResourceAsStream("/dtn/legacy-afs-v1.json")) {
        transfer = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(fixture, Transfer.class);
      } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
      var result = new DtnProcessor(codec,directory).receive(transfer.getTestId(),transfer);
      assertEquals(server.shared.model.DtnObservationView.fromRecords(records),result.getObservations());
      assertFalse(result.getPvt().getFirst().isPositionValid());
    }
  }
}
