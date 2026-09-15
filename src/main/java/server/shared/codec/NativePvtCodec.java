package server.shared.codec;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import server.shared.codec.GrawCodec;
import server.shared.model.DtnModels.Pvt;

/** 같은 AFS DLL의 PVT 확장을 호출한다. RTKLIB 구조체와 알고리즘은 Java로 복제하지 않는다. */
public final class NativePvtCodec implements AutoCloseable {
  public interface Api extends Library {
    int lnis_pvt_get_abi_version();
    Pointer lnis_pvt_create();
    void lnis_pvt_destroy(Pointer context);
    int lnis_pvt_gps_navigation(Pointer context, int prn, int week, int[] words, int count);
    int lnis_pvt_gps_solve(Pointer context, int week, double tow, double[] observations,
        int count, double[] result, byte[] message, int messageSize);
  }
  private final Api api;
  private final Pointer context;

  public NativePvtCodec(Path directory) {
    api = Native.load(NativeLibraryPath.resolve(directory).toString(), Api.class);
    if (api.lnis_pvt_get_abi_version() != 1) throw new IllegalStateException("PVT DLL ABI mismatch");
    context = api.lnis_pvt_create();
    if (context == null) throw new IllegalStateException("PVT context allocation failed");
  }

  /** 수집 레코드 순서대로 항법정보를 갱신해 Sender/Receiver의 동일 초기 상태를 보장한다. */
  public List<Pvt> calculate(List<byte[]> records) {
    int week = records.stream().map(GrawCodec::decode).map(GrawCodec.Envelope::message)
        .filter(GrawCodec.ObservationEpoch.class::isInstance)
        .map(GrawCodec.ObservationEpoch.class::cast).mapToInt(GrawCodec.ObservationEpoch::week)
        .findFirst().orElseThrow(() -> new IllegalArgumentException("RAWX 관측 데이터가 없습니다."));
    List<Pvt> results = new ArrayList<>();
    for (byte[] record : records) {
      var message = GrawCodec.decode(record).message();
      if (message instanceof GrawCodec.NavigationUpdate nav && nav.constellationId() == 0
          && nav.signalId() == 0 && nav.words().size() == 10) {
        // SFRBX v2의 signalId 위치는 reserved0일 수 있어 0만으로 LNAV를 판별할 수 없다.
        // GPS L2/L5 CNAV도 10 word이므로 실제 LNAV preamble 위치를 확인한다.
        // 기존 GRAW 저장 형식은 유지하고 이번 GPS L1 계산에서만 다른 형식을 제외한다.
        if (((nav.words().getFirst() >>> 22) & 0xff) != 0x8b) continue;
        // UBX-SFRBX GPS LNAV word의 하위 6개 parity bit를 제외해 RTKLIB의 24-bit 형식으로 연결한다.
        int[] words = nav.words().stream().mapToInt(word -> (int)((word >>> 6) & 0xffffff)).toArray();
        int status = api.lnis_pvt_gps_navigation(context, nav.satelliteId(), week, words, words.length);
        if (status < 0) throw new IllegalArgumentException("유효하지 않은 GPS LNAV 항법 데이터입니다.");
      } else if (message instanceof GrawCodec.ObservationEpoch epoch) {
        week = epoch.week();
        results.add(solve(epoch));
      }
    }
    return results;
  }

  private Pvt solve(GrawCodec.ObservationEpoch epoch) {
    var observations = epoch.observations().stream()
        .filter(o -> o.constellationId() == 0 && o.signalId() == 0 && (o.trackingStatus() & 1) != 0)
        .toList();
    Pvt pvt = new Pvt();
    pvt.setWeek(epoch.week());
    pvt.setTowSeconds(epoch.receiverTowSeconds());
    if (observations.isEmpty()) {
      pvt.setMessage("유효한 GPS L1 C/A 관측값 없음");
      return pvt;
    }
    double[] input = new double[observations.size()*4];
    for (int i = 0; i < observations.size(); i++) {
      var o = observations.get(i);
      input[i*4] = o.satelliteId();
      input[i*4+1] = o.pseudorangeMeters();
      input[i*4+2] = o.dopplerHz();
      input[i*4+3] = o.carrierToNoiseDbHz();
    }
    return solve(epoch.week(), epoch.receiverTowSeconds(), input);
  }

  /** I/Q 복원 관측도 동일 RTKLIB 연결부를 사용한다. 참조 PVT는 받지 않는다. */
  public void navigation(int prn, int week, int[] words) {
    if (api.lnis_pvt_gps_navigation(context, prn, week, words, words.length) < 0)
      throw new IllegalArgumentException("유효하지 않은 GPS LNAV 항법 데이터입니다.");
  }

  public Pvt solve(int week, double tow, double[] input) {
    if (input.length == 0 || input.length % 4 != 0 || input.length > 128)
      throw new IllegalArgumentException("PVT 관측 개수 오류");
    Pvt pvt = new Pvt(); pvt.setWeek(week); pvt.setTowSeconds(tow);
    double[] result = new double[9];
    byte[] error = new byte[256];
    int status = api.lnis_pvt_gps_solve(context, week, tow,
        input, input.length / 4, result, error, error.length);
    if (status < 0) throw new IllegalArgumentException("PVT 입력 범위 오류");
    int length = 0;
    while (length < error.length && error[length] != 0) length++;
    pvt.setMessage(new String(error, 0, length, StandardCharsets.UTF_8));
    pvt.setPositionValid(status == 1);
    if (status == 1) {
      pvt.setEcefMeters(Arrays.copyOfRange(result, 0, 3));
      pvt.setVelocityValid(result[8] != 0);
      if (pvt.isVelocityValid()) pvt.setVelocityMetersPerSecond(Arrays.copyOfRange(result, 3, 6));
      pvt.setReceiverClockBiasSeconds(result[6]);
      pvt.setSatellitesUsed((int)result[7]);
    }
    return pvt;
  }

  @Override public void close() { api.lnis_pvt_destroy(context); }
}
