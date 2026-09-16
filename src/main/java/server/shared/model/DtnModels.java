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
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private HdtnConfig hdtnConfig;
    private IqFile file;
    /** AFS: observations + residual navigation; I/Q: GPS LNAV assistance only. */
    private Metadata metadata;
    private String sourceSha256;
    private int recordCount;
    private int prn = 1;
    private List<Frame> frames;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<AfsSatellite> satellites;
    /** RAW transport preserves canonical GRAW bytes, not UBX serial bytes. */
    private String grawBase64;
    /** Display/comparison only; never used as receiver solver input. */
    private List<Pvt> referencePvt;
  }

  /** 시험별 HDTN 어댑터 설정. 실제 정책 적용은 어댑터가 담당한다. */
  @Data @NoArgsConstructor
  public static class HdtnConfig {
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(1)
    private Integer maxNumberOfBundlesInPipeline;
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(9007199254740991L)
    private Long maxSumOfBundleBytesInPipeline;
    @jakarta.validation.constraints.NotNull
    private Boolean enforceBundlePriority;
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(0)
    private Integer neighborDepletedStorageDelaySeconds;
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(9007199254740991L)
    private Long maxBundleSizeBytes;
    /** 기존 여섯 항목만 보내는 클라이언트는 생략할 수 있다. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @jakarta.validation.constraints.Min(1400)
    @jakarta.validation.constraints.Max(1000000)
    private Integer tcpclMaxSegmentSizeBytes;
    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}")
    private String storageDeletionPolicy;
  }

  /** A reference to a completed local shared file, never the I/Q binary body. */
  public record IqFile(String filePath, long sizeBytes, String sha256, int durationSeconds,
      int sampleRateHz, String sampleFormat, int quantizationBits) {}

  @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.DEDUCTION)
  @com.fasterxml.jackson.annotation.JsonSubTypes({
      @com.fasterxml.jackson.annotation.JsonSubTypes.Type(IqMetadata.class),
      @com.fasterxml.jackson.annotation.JsonSubTypes.Type(AfsMetadata.class),
      @com.fasterxml.jackson.annotation.JsonSubTypes.Type(AfsGroupedMetadata.class)})
  public sealed interface Metadata permits IqMetadata, AfsMetadata, AfsGroupedMetadata {}

  public record IqNavigation(int prn, List<Integer> words24) {}
  public record IqMetadata(String signal, String pvtMethod, int week, double towSeconds,
      String trajectory, List<Integer> prns, List<IqNavigation> gpsLnav) implements Metadata {}

  /** Navigation words are residuals: restore SB2 fields before interpreting them as SFRBX. */
  public record AfsMetadata(List<AfsRecord> records) implements Metadata {}
  /** v3: shared epoch/envelope data only; measurements and navigation live with their satellite. */
  public record AfsGroupedMetadata(List<AfsIndexedRecord> commonRecords) implements Metadata {}
  public record AfsSatellite(int constellationId, int prn, List<Frame> frames,
      AfsSatelliteMetadata metadata) {}
  public record AfsSatelliteMetadata(List<AfsMeasurement> observations,
      List<AfsIndexedRecord> navigationSupplement) {}
  public record AfsIndexedRecord(int recordIndex, AfsRecord record) {}
  public record AfsMeasurement(int recordIndex, int measurementIndex, int week, double towSeconds,
      server.shared.codec.GrawCodec.Observation observation) {}
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record AfsRecord(UUID testId, UUID messageId, long sequence, java.time.Instant capturedAt,
      server.shared.codec.GrawCodec.ObservationEpoch observation,
      server.shared.codec.GrawCodec.NavigationUpdate navigation,
      server.shared.codec.GrawCodec.ReceiverMetadata receiver) {}

  /** frameBase64는 반드시 750바이트 AFS 프레임이며 관측 시각은 복원된 GRAW에 있다. */
  @Data @NoArgsConstructor
  public static class Frame {
    private int index;
    private int week;
    private int afsItow;
    private int toi;
    private String frameBase64;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer prn;
    /** Zero-based metadata.records indices for GPS LNAV subframes 1, 2, 3. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<Integer> navigationRecordIndices;
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
