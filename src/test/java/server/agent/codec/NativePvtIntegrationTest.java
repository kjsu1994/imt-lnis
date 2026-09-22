package server.agent.codec;
import server.shared.codec.NativePvtCodec;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import server.agent.dtn.DtnProcessor;
import server.shared.codec.*;
import server.shared.model.DtnModels;

/** 실제 DLL 호출과 AFS 왕복을 검증한다. 항법 부족 입력을 정상 PVT로 오판하지 않아야 한다. */
@EnabledOnOs(OS.WINDOWS)
public class NativePvtIntegrationTest {
  private Path candidate() { return Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64")); }

  @Test void afsBinaryCompatibilityAndDtnRoundTrip() {
    try (var previous = NativeAfsCodec.load(Path.of("native/bin/win-x64"));
         var extended = NativeAfsCodec.load(candidate())) {
      Random random = new Random(20260907);
      for (int toi : List.of(0, 1, 33, 99)) {
        byte[] sb2 = bits(random, 1176), sb3 = bits(random, 846), sb4 = bits(random, 846);
        byte[] expected = previous.encode(toi, sb2, sb3, sb4);
        assertArrayEquals(expected, extended.encode(toi, sb2, sb3, sb4));
        var decoded = extended.decode(toi, expected);
        assertTrue(decoded.sb2Valid() && decoded.sb3Valid() && decoded.sb4Valid());
        assertArrayEquals(sb2, decoded.sb2());
        assertArrayEquals(sb3, decoded.sb3());
        assertArrayEquals(sb4, decoded.sb4());
      }
      var processor = new DtnProcessor(extended, candidate());
      UUID id = UUID.randomUUID();
      assertThrows(IllegalArgumentException.class, () -> processor.prepare(id, sample()));
      var prepared = processor.prepare(id, sample(), true);
      assertFalse(prepared.getPvt().getFirst().isPositionValid());
      var received = processor.receive(id, prepared.getTransfer());
      assertEquals(prepared.getPvt(), received.getPvt());
      prepared.getTransfer().setSourceSha256("0".repeat(64));
      assertThrows(IllegalArgumentException.class, () -> processor.receive(id, prepared.getTransfer()));
    }
  }

  @Test void rejectsMalformedNativeInputWithoutFabricatingCoordinates() {
    try (var pvt = new NativePvtCodec(candidate())) {
      var result = pvt.calculate(GrawCodec.splitLengthPrefixed(sample()));
      assertEquals(1, result.size());
      assertFalse(result.getFirst().isPositionValid());
      assertNull(result.getFirst().getEcefMeters());
      assertFalse(result.getFirst().isVelocityValid());
    }
  }

  public static byte[] sample() {
    var observations = new ArrayList<GrawCodec.Observation>();
    for (int prn = 1; prn <= 6; prn++)
      observations.add(new GrawCodec.Observation(22000000+prn*1000, 0, -1000,
          0, prn, 0, 0, 1000, 45, 1, 1, 1, 1));
    byte[] record = GrawCodec.encode(new GrawCodec.Envelope(UUID.randomUUID(), UUID.randomUUID(), 0,
        Instant.parse("2026-09-07T00:00:00Z"), new GrawCodec.ObservationEpoch(100000, 2400, 18, 1, 1, observations)));
    return ByteBuffer.allocate(4+record.length).putInt(record.length).put(record).array();
  }

  @Test void reservedSignalByteDoesNotTreatCnavAsLnav() throws Exception {
    var records = new ArrayList<>(GrawCodec.splitLengthPrefixed(validSample()));
    var words = new ArrayList<Long>(Collections.nCopies(10, 0L));
    // CNAV preamble은 word의 최상위 8 bit에 있다. v2 reserved0은 0이다.
    words.set(0, 0x8b000000L);
    records.addFirst(GrawCodec.encode(new GrawCodec.Envelope(UUID.randomUUID(), UUID.randomUUID(), 0,
        Instant.parse("2026-09-07T00:00:00Z"),
        new GrawCodec.NavigationUpdate(0, 1, 0, 0, 2, words))));
    try (var expected = new NativePvtCodec(candidate()); var actual = new NativePvtCodec(candidate())) {
      assertEquals(expected.calculate(GrawCodec.splitLengthPrefixed(validSample())), actual.calculate(records));
    }
  }

  @Test void validGpsSolutionSurvivesAfsRoundTrip() throws Exception {
    try (var codec = NativeAfsCodec.load(candidate())) {
      DtnProcessor processor = new DtnProcessor(codec, candidate());
      UUID id = UUID.randomUUID();
      var tx = processor.prepare(id, validSample());
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
      var wire = mapper.readValue(mapper.writeValueAsBytes(tx.getTransfer()), DtnModels.Transfer.class);
      assertEquals(4, wire.getSchemaVersion());
      assertEquals("LNIS-AFS-GNSS-v4", wire.getFormat());
      assertInstanceOf(DtnModels.AfsGroupedMetadata.class, wire.getMetadata());
      assertNull(wire.getFrames());
      assertEquals(32,wire.getSatellites().size());
      for (var frame : wire.getSatellites().stream().flatMap(s -> s.frames().stream()).toList()) {
        var decoded = codec.decode(frame.getToi(), Base64.getDecoder().decode(frame.getFrameBase64()));
        var payload = AfsPvtFrameCodec.decode(decoded.sb2(), decoded.sb3(), decoded.sb4());
        assertEquals(frame.getPrn(), payload.prn());
        assertEquals(100000.0, payload.tow());
        if (frame.getPrn() == 19) {
          // Independent existing SB2 reader + known native/test_pvt.c fixture values.
          // This reader's PRN selects a comparison almanac only; SB2 itself carries no PRN.
          // Use supported profile 1 to independently inspect the PRN 19 frame's actual bit values.
          var eph = server.agent.afs.Sb2PayloadCodec.decode(decoded.sb2(),1,2400,83);
          assertEquals(99984,eph.toeSeconds()); assertEquals(99984,eph.tocSeconds());
          assertEquals(0.01,eph.eccentricity(),Math.scalb(1.0,-32));
          assertEquals(Math.sqrt(26560000),eph.sqrtSemiMajorAxis(),Math.scalb(1.0,-19));
          assertEquals(Math.toRadians(55),eph.inclinationRadians(),2e-9);
          assertEquals(-Math.PI,eph.ascendingNodeRadians(),2e-9);
          assertEquals(-Math.PI + 18 * 2 * Math.PI / 32,eph.meanAnomalyRadians(),2e-9);
          assertEquals(0,eph.argumentOfPerigeeRadians());
          assertEquals(0,eph.af0Seconds()); assertEquals(0,eph.af1SecondsPerSecond());
          assertTrue(eph.headerMatchesPacket() && eph.tailTestPatternValid());
        }
      }
      var misleadingReference = new DtnModels.Pvt(); misleadingReference.setPositionValid(true);
      misleadingReference.setEcefMeters(new double[]{1,2,3});
      wire.setReferencePvt(List.of(misleadingReference)); // Comparison fields cannot drive receiver PVT.
      var rx = processor.receive(id, wire);
      assertEquals(tx.getObservations().records(), rx.getObservations().records());
      assertEquals(tx.getObservations().epochs(), rx.getObservations().epochs());
      assertEquals(tx.getObservations().navigation(), rx.getObservations().navigation());
      assertEquals(tx.getObservations().receiver(), rx.getObservations().receiver());
      assertEquals("AFS_V4", rx.getObservations().frameInput().source());
      var pvt = tx.getPvt().getFirst();
      assertTrue(pvt.isPositionValid(), pvt.getMessage());
      assertTrue(pvt.isVelocityValid());
      assertEquals(tx.getPvt(), rx.getPvt());
      double[] expected = {-3049086.2377217,4046274.10244263,3861624.97495251};
      for (int i = 0; i < 3; i++) assertEquals(expected[i], pvt.getEcefMeters()[i], 0.01);
      wire.setSourceSha256("0".repeat(64));
      assertThrows(IllegalArgumentException.class, () -> processor.receive(id, wire));
    }
  }

  @Test void rawUsesSameEarthSolverAndRejectsDispatchOrHashChanges() throws Exception {
    try (var codec = NativeAfsCodec.load(candidate())) {
      var processor = new DtnProcessor(codec, candidate());
      UUID id = UUID.randomUUID(); byte[] input = validSample();
      var prepared = processor.prepare(id, input, true);
      var transfer = prepared.getTransfer();
      assertEquals("GNSS_RAW", transfer.getTestType());
      assertNull(transfer.getFrames());
      assertArrayEquals(input, Base64.getDecoder().decode(transfer.getGrawBase64()));
      assertEquals(prepared.getPvt(), processor.receive(id, transfer).getPvt());
      assertTrue(prepared.getPvt().getFirst().isPositionValid());
      assertTrue(prepared.getPvt().getFirst().isVelocityValid());
      transfer.setTestType("IQ_SAMPLE");
      assertThrows(IllegalArgumentException.class, () -> processor.receive(id, transfer));
      transfer.setTestType("GNSS_RAW"); transfer.setSourceSha256("0".repeat(64));
      assertThrows(IllegalArgumentException.class, () -> processor.receive(id, transfer));
    }
  }

  /** native/test_pvt.c가 생성한 합성 항법/관측값이다. 실제 장비 로그가 아니다. */
  public static byte[] validSample() throws Exception {
    List<GrawCodec.Message> messages = new ArrayList<>();
    List<GrawCodec.Observation> observations = new ArrayList<>();
    try (var input = NativePvtIntegrationTest.class.getResourceAsStream("/dtn/synthetic-gps.txt")) {
      assertNotNull(input);
      for (String line : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
        String[] fields = line.split(" ");
        if (fields[0].equals("N")) {
          List<Long> words = new ArrayList<>();
          for (int i = 2; i < fields.length; i++) words.add(Long.parseLong(fields[i]));
          messages.add(new GrawCodec.NavigationUpdate(0, Integer.parseInt(fields[1]), 0, 0, 2, words));
        } else if (fields[0].equals("O")) {
          observations.add(new GrawCodec.Observation(Double.parseDouble(fields[2]), 0,
              Float.parseFloat(fields[3]), 0, Integer.parseInt(fields[1]), 0, 0, 1000, 45, 1, 1, 1, 1));
        }
      }
    }
    messages.add(new GrawCodec.ObservationEpoch(100000, 2400, 18, 1, 1, observations));
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    UUID capture = UUID.nameUUIDFromBytes("synthetic-earth-pvt-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    int sequence = 0;
    for (var message : messages) {
      byte[] record = GrawCodec.encode(new GrawCodec.Envelope(capture, UUID.nameUUIDFromBytes((capture + ":" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8)), sequence++,
          Instant.parse("2026-09-07T00:00:00Z"), message));
      out.writeBytes(ByteBuffer.allocate(4).putInt(record.length).array()); out.writeBytes(record);
    }
    return out.toByteArray();
  }
  private static byte[] bits(Random random, int count) {
    byte[] value = new byte[count];
    for (int i = 0; i < count; i++) value[i] = (byte)random.nextInt(2);
    return value;
  }
}
