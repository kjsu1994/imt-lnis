package server.shared.model;

import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/** DTN 외부 전달 규격이다. BPv7 헤더 생성은 외부 시스템이 담당한다. */
public final class DtnModels {
  public static final String PROFILE = "POCKETSDR-GPS-L1CA-SPP-v1";
  public static final int MAX_INPUT_BYTES = 1048576;
  public static final int MAX_JSON_BYTES = 16777216;
  private DtnModels() {}

  /** JSON 하나가 수집 종료 후 생성한 AFS 프레임 전체를 포함한다. */
  @Data @NoArgsConstructor
  public static class Transfer {
    private int schemaVersion = 1;
    private UUID testId;
    private String profile = PROFILE;
    private String format = "LNIS-GRAW-AFS-v1";
    /** Adapter dispatch key; absent in legacy AFS v1 requests. */
    private String testType = "AFS_METADATA";
    private String senderMode;
    private String receiverMode;
    private IqFile file;
    /** I/Q tracking supplies observations; this metadata supplies GPS LNAV only. */
    private IqMetadata metadata;
    private String sourceSha256;
    private int recordCount;
    private int prn = 1;
    private List<Frame> frames;
    /** RAW transport preserves canonical GRAW bytes, not UBX serial bytes. */
    private String grawBase64;
    /** Display/comparison only; never used as receiver solver input. */
    private List<Pvt> referencePvt;
  }

  /** A reference to a completed local shared file, never the I/Q binary body. */
  public record IqFile(String filePath, long sizeBytes, String sha256, int durationSeconds,
      int sampleRateHz, String sampleFormat, int quantizationBits) {}

  public record IqNavigation(int prn, List<Integer> words24) {}
  public record IqMetadata(String signal, String pvtMethod, int week, double towSeconds,
      String trajectory, List<Integer> prns, List<IqNavigation> gpsLnav) {}

  /** frameBase64는 반드시 750바이트 AFS 프레임이며 관측 시각은 복원된 GRAW에 있다. */
  @Data @NoArgsConstructor
  public static class Frame {
    private int index;
    private int week;
    private int afsItow;
    private int toi;
    private String frameBase64;
  }

  /** T는 DTN 도착 시각이 아닌 관측 시각 및 수신기 시계 오차다. */
  @Data @NoArgsConstructor
  public static class Pvt {
    private int week;
    private double towSeconds;
    private boolean positionValid;
    private boolean velocityValid;
    private double[] ecefMeters;
    private double[] velocityMetersPerSecond;
    private Double receiverClockBiasSeconds;
    private int satellitesUsed;
    private String message;
  }

  /** 실행기의 독립 계산 결과와 전송 데이터. */
  @Data @NoArgsConstructor
  public static class AgentResult {
    private Transfer transfer;
    private List<Pvt> pvt;
    private DtnObservationView observations;
    private String error;
  }
}
