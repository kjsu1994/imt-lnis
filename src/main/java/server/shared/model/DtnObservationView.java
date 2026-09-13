package server.shared.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import server.shared.codec.GrawCodec;

/** UI projection only: never added to the external AFS transfer payload. */
public record DtnObservationView(List<Epoch> epochs, int navigationCount,
                                 GrawCodec.ReceiverMetadata receiver) {
  public record Epoch(Instant capturedAt, GrawCodec.ObservationEpoch observation) {}

  public static DtnObservationView fromRecords(List<byte[]> records) {
    List<Epoch> epochs = new ArrayList<>();
    int navigation = 0;
    GrawCodec.ReceiverMetadata receiver = null;
    for (byte[] bytes : records) {
      var record = GrawCodec.decode(bytes);
      if (record.message() instanceof GrawCodec.ObservationEpoch observation)
        epochs.add(new Epoch(record.capturedAt(), observation));
      else if (record.message() instanceof GrawCodec.NavigationUpdate) navigation++;
      else if (record.message() instanceof GrawCodec.ReceiverMetadata metadata) receiver = metadata;
    }
    return new DtnObservationView(List.copyOf(epochs), navigation, receiver);
  }
}
