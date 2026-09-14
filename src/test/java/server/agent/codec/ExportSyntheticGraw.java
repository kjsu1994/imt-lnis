package server.agent.codec;

import java.nio.file.*;
import server.shared.codec.*;

/** Development artifact generator. Never included in the production JAR. */
public final class ExportSyntheticGraw {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args.length == 0 ? "build/dtn-example" : args[0]);
    Files.createDirectories(root);
    byte[] bytes = NativePvtIntegrationTest.validSample();
    try (var codec = new NativePvtCodec(Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64")))) {
      var pvt = codec.calculate(GrawCodec.splitLengthPrefixed(bytes)).getFirst();
      if (!pvt.isPositionValid() || !pvt.isVelocityValid()) throw new IllegalStateException(pvt.getMessage());
      Files.write(root.resolve("synthetic-earth-pvt.graw"), bytes);
      Files.writeString(root.resolve("synthetic-earth-pvt.json"), new com.fasterxml.jackson.databind.ObjectMapper()
          .writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
              "source", "SYNTHETIC_NOT_F9T_MEASUREMENTS", "profile", "POCKETSDR-GPS-L1CA-SPP-v1",
              "sha256", Hashing.hex(Hashing.sha256Digest().digest(bytes)), "expectedPvt", pvt)));
    }
    System.out.println("Synthetic PVT fixture: " + root.toAbsolutePath());
  }
}
