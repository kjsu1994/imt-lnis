package server.agent.gnss;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import server.shared.codec.GrawCodec;
import server.shared.model.DtnModels;

/** Keep navigation in arrival order and retain only the first independently solvable epoch. */
public final class SingleEpochCapture {
  private final List<byte[]> navigation = new ArrayList<>();
  private final Predicate<List<byte[]>> solvable;
  private int bytes;

  public SingleEpochCapture(Predicate<List<byte[]>> solvable) { this.solvable = solvable; }

  public List<byte[]> accept(byte[] record) {
    var message = GrawCodec.decode(record).message();
    if (bytes + record.length + 4 > DtnModels.MAX_INPUT_BYTES)
      throw new IllegalStateException("수집 입력이 1 MiB를 초과했습니다. 장치 출력을 확인하세요.");
    if (message instanceof GrawCodec.ObservationEpoch) {
      var candidate = new ArrayList<>(navigation);
      candidate.add(record);
      return solvable.test(candidate) ? List.copyOf(candidate) : null;
    }
    navigation.add(record);
    bytes += record.length + 4;
    return null;
  }
}
