package server.shared.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import server.shared.codec.GrawCodec;

/** UI projection only: never added to the external AFS transfer payload. */
public record DtnObservationView(List<Epoch> epochs, int navigationCount,
                                 GrawCodec.ReceiverMetadata receiver,
                                 List<Navigation> navigation,
                                 List<StoredRecord> records,
                                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                 FrameInput frameInput) {
  public record FrameInput(String source, int frameCount, List<Epoch> epochs, List<Navigation> navigation) {}

  public DtnObservationView(List<Epoch> epochs, int navigationCount, GrawCodec.ReceiverMetadata receiver,
      List<Navigation> navigation, List<StoredRecord> records) {
    this(epochs, navigationCount, receiver, navigation, records, null);
  }

  public DtnObservationView withFrameInput(List<byte[]> input, int count) {
    var decoded = fromRecords(input);
    return new DtnObservationView(epochs, navigationCount, receiver, navigation, records,
        new FrameInput("AFS_V4", count, decoded.epochs(), decoded.navigation()));
  }

  public record Epoch(Instant capturedAt, GrawCodec.ObservationEpoch observation) {}
  public record Navigation(long sequence, Instant capturedAt, GrawCodec.NavigationUpdate message) {}
  public record StoredRecord(long sequence, Instant capturedAt, String type, Object message) {}

  public static DtnObservationView fromRecords(List<byte[]> records) {
    List<Epoch> epochs = new ArrayList<>();
    List<Navigation> navigation = new ArrayList<>();
    List<StoredRecord> decoded = new ArrayList<>();
    GrawCodec.ReceiverMetadata receiver = null;
    for (byte[] bytes : records) {
      var record = GrawCodec.decode(bytes);
      decoded.add(new StoredRecord(record.sequence(), record.capturedAt(),
          record.message().type().name(), record.message()));
      if (record.message() instanceof GrawCodec.ObservationEpoch observation)
        epochs.add(new Epoch(record.capturedAt(), observation));
      else if (record.message() instanceof GrawCodec.NavigationUpdate message)
        navigation.add(new Navigation(record.sequence(), record.capturedAt(), message));
      else if (record.message() instanceof GrawCodec.ReceiverMetadata metadata) receiver = metadata;
    }
    return new DtnObservationView(List.copyOf(epochs), navigation.size(), receiver,
        List.copyOf(navigation), List.copyOf(decoded));
  }
}
