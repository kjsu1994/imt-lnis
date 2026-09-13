package server.agent.codec;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import server.agent.dtn.DtnProcessor;
import server.agent.gnss.UbloxParser;
import server.shared.codec.GrawCodec;
import server.shared.codec.Hashing;
import server.shared.model.DtnModels;

/** Opt-in development fixture builder. No fabricated observations or PVT results. */
@EnabledIfSystemProperty(named = "lnis.f9t.source", matches = ".+")
class F9tExampleTest {
  @Test void publicObservationsRoundTripWithoutInventingNavigation() throws Exception {
    Path source = Path.of(System.getProperty("lnis.f9t.source"));
    byte[] compressed = Files.readAllBytes(source);
    assertEquals("1ABCE8A84A82B4AD9DB45362244E54CFDCA85016A79962BB02B2DFC8B5D29B87",
        Hashing.hex(Hashing.sha256Digest().digest(compressed)));
    byte[] raw;
    try (var stream = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
      raw = stream.readAllBytes();
    }
    var frames = new UbloxParser().push(raw, raw.length);
    assertTrue(frames.stream().noneMatch(f -> f.messageClass() == 2 && f.messageId() == 0x13),
        "이 고정 예제는 SFRBX가 없는 관측값 전달 전용입니다.");
    Path nativePath = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
    List<GrawCodec.Message> navigation = new ArrayList<>();
    navigation.add(new GrawCodec.ReceiverMetadata("F9T (공개 UBX 기록)", "출처 파일 참조", "file", 0,
        "개발용 예제 / 원본 PC 수집시각 미제공"));
    byte[] candidate = null;
    for (var frame : frames) {
      var message = UbloxParser.toCanonical(frame);
      if (message instanceof GrawCodec.NavigationUpdate) navigation.add(message);
      if (!(message instanceof GrawCodec.ObservationEpoch)) continue;
      // This pinned F9T recording uses RAWX v1; do not silently normalize another version.
      assertEquals(1, Byte.toUnsignedInt(frame.payload()[13]));
      var messages = new ArrayList<>(navigation);
      messages.add(message);
      byte[] bytes = encode(messages);
      if (bytes.length > DtnModels.MAX_INPUT_BYTES) break;
      candidate = bytes;
      break;
    }
    assertNotNull(candidate, "공개 기록에서 관측 시점을 찾지 못했습니다.");
    try (var afs = NativeAfsCodec.load(nativePath)) {
      var processor = new DtnProcessor(afs, nativePath);
      UUID id = UUID.randomUUID();
      var tx = processor.prepare(id, candidate);
      var rx = processor.receive(id, tx.getTransfer());
      assertEquals(tx.getPvt(), rx.getPvt());
      assertEquals(tx.getObservations(), rx.getObservations());
      assertEquals(1, tx.getPvt().size());
      assertFalse(tx.getPvt().getFirst().isPositionValid(), "항법정보가 없는데 유효 PVT로 표시하면 안 됩니다.");
      assertFalse(rx.getPvt().getFirst().isVelocityValid());
      assertEquals(0, tx.getObservations().navigationCount());
      Path output = Path.of("build/dtn-example/f9t-example.graw");
      Files.createDirectories(output.getParent());
      Files.write(output, candidate);
      Files.writeString(Path.of("build/dtn-example/verification.txt"),
          "Source SHA256: " + Hashing.hex(Hashing.sha256Digest().digest(compressed)) + "\n" +
          "GRAW SHA256: " + Hashing.hex(Hashing.sha256Digest().digest(candidate)) + "\n" +
          "Bytes: " + candidate.length + "\nPVT: " + tx.getPvt() + "\n" +
          "Exact AFS round trip observations: true\nPVT comparison unavailable: no SFRBX navigation in source\n");
    }
  }

  private static byte[] encode(List<GrawCodec.Message> messages) {
    UUID id = UUID.nameUUIDFromBytes("F9T-L2-5min".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var output = new ByteArrayOutputStream();
    for (int i = 0; i < messages.size(); i++) {
      // Original PC timestamps are unavailable. Epoch zero explicitly means unspecified in this fixture.
      var record = GrawCodec.encode(new GrawCodec.Envelope(id, new UUID(0, i), i, Instant.EPOCH, messages.get(i)));
      output.writeBytes(ByteBuffer.allocate(4).putInt(record.length).array()); output.writeBytes(record);
    }
    return output.toByteArray();
  }
}
