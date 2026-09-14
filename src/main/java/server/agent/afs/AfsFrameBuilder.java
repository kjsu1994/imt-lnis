package server.agent.afs;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import server.agent.codec.NativeAfsCodec;
import server.shared.codec.GrawCodec;
import server.shared.model.LnisModels.TestOptions;
import server.shared.model.LnisModels.TestType;

/**
 * canonical GRAW 레코드를 AFS 프레임으로 조립하고 시험 오류를 주입한다.
 *
 * <p>각 GRAW 레코드를 CRC가 포함된 fragment로 나누고 두 fragment를 SB3/SB4에 배치한다. SB2에는 week/AFS ITOW와 선택 PRN의
 * LANS ephemeris를 기록한다. Test B~D의 오류 위치는 seed와 frame index로 계산해 기존 WPF 시험과 반복 실행 결과가 같도록 유지한다.
 */
public final class AfsFrameBuilder {
  /** Native Codec으로 인코딩한 논리 AFS 프레임과 시간 좌표다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Frame {
    /** GPS week 번호다. */
    int week;

    /** GPS 주 내 1,200초 구간 번호인 AFS ITOW다. u-blox iTOW(ms)와 다르다. */
    int intervalOfWeek;

    /** 1,200초 구간 안의 AFS TOI 값이며 범위는 0~99다. */
    int timeOfInterval;

    /** 750 byte, 즉 6,000 bit로 구성된 AFSFrame 원문이다. */
    byte[] payload;
  }

  /** 시험 오류가 주입된 프레임과 실제 비트 위치를 화면 로그에 전달하기 위한 상세 정보다. */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class InjectionDetail {
    /** 오류가 주입된 0부터 시작하는 논리 프레임 번호다. */
    int frameIndex;

    /** RANDOM_BIT_ERROR, BURST_BIT_ERROR 또는 SYNC_DAMAGE 오류 방식이다. */
    String mode;

    /** 실제로 반전한 0~5,999 범위의 프레임 비트 위치 목록이다. */
    List<Integer> bitPositions;

    public InjectionDetail(
        /** 오류가 주입된 0부터 시작하는 논리 프레임 번호다. */
        int frameIndex,
        /** RANDOM_BIT_ERROR, BURST_BIT_ERROR 또는 SYNC_DAMAGE 오류 방식이다. */
        String mode,
        /** 실제로 반전한 0~5,999 범위의 프레임 비트 위치 목록이다. */
        List<Integer> bitPositions) {
      bitPositions = List.copyOf(bitPositions);

      this.frameIndex = frameIndex;
      this.mode = mode;
      this.bitPositions = bitPositions;
    }
  }

  /** 조립된 프레임과 프레임별 오류 주입 내역을 함께 보관한다. */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Prepared {
    /** 시험 오류를 넣기 전 비교 기준 프레임 목록이다. */
    List<Frame> referenceFrames;

    /** 시험 오류를 적용해 실제로 전송할 프레임 목록이다. */
    List<Frame> frames;

    /** 오류가 하나 이상 주입된 논리 프레임 개수다. 주입 비트 총합과는 다르다. */
    int injectedFrameCount;

    /** 이 프레임 묶음을 만들 때 사용한 원본 GRAW 레코드 개수다. */
    long recordCount;

    /** 프레임별 오류 방식과 실제 반전 위치를 담은 진단 목록이다. */
    List<InjectionDetail> injections;

    public Prepared(
        /** 시험 오류를 넣기 전 비교 기준 프레임 목록이다. */
        List<Frame> referenceFrames,
        /** 시험 오류를 적용해 실제로 전송할 프레임 목록이다. */
        List<Frame> frames,
        /** 오류가 하나 이상 주입된 논리 프레임 개수다. 주입 비트 총합과는 다르다. */
        int injectedFrameCount,
        /** 이 프레임 묶음을 만들 때 사용한 원본 GRAW 레코드 개수다. */
        long recordCount,
        /** 프레임별 오류 방식과 실제 반전 위치를 담은 진단 목록이다. */
        List<InjectionDetail> injections) {
      referenceFrames = List.copyOf(referenceFrames);
      frames = List.copyOf(frames);
      injections = List.copyOf(injections);

      this.referenceFrames = referenceFrames;
      this.frames = frames;
      this.injectedFrameCount = injectedFrameCount;
      this.recordCount = recordCount;
      this.injections = injections;
    }
  }

  private final NativeAfsCodec codec;

  public AfsFrameBuilder(NativeAfsCodec codec) {
    this.codec = codec;
  }

  /** 전체 입력 record를 시간 순서의 AFS frame 목록과 오류 주입 개수로 변환한다. */
  public Prepared prepare(List<byte[]> records, TestOptions options, int prn) {
    return prepare(records, options, prn, () -> {});
  }

  public Prepared prepare(List<byte[]> records, TestOptions options, int prn, Runnable checkpoint) {
    checkpoint.run();
    if (records.isEmpty()) throw new IllegalArgumentException("capture.graw is empty");
    List<byte[]> blocks = new ArrayList<>();
    // GRAW record 하나가 여러 86-byte payload 조각이 될 수 있으며 이후 프레임당 두 조각을 소비한다.
    for (int i = 0; i < records.size(); i++) {
      checkpoint.run();
      blocks.addAll(AfsRawFragmentCodec.fragment(i, records.get(i)));
    }
    int[] time = timeFrom(records);
    int totalFrames = (blocks.size() + 1) / 2;
    int injected = 0;
    List<Frame> frames = new ArrayList<>(totalFrames);
    List<Frame> referenceFrames = new ArrayList<>(totalFrames);
    List<InjectionDetail> injections = new ArrayList<>();
    for (int index = 0; index < blocks.size(); index += 2) {
      checkpoint.run();
      // 조각 수가 홀수면 마지막 조각을 SB3/SB4 양쪽에 넣는다. 재조립기는 동일 조각 중복을 허용한다.
      byte[] second = index + 1 < blocks.size() ? blocks.get(index + 1) : blocks.get(index);
      byte[] encoded =
          codec.encode(
              time[2],
              Sb2PayloadCodec.encode(time[0], time[1], prn),
              AfsRawFragmentCodec.toSbBits(blocks.get(index)),
              AfsRawFragmentCodec.toSbBits(second));
      checkpoint.run();
      byte[] reference = encoded.clone();
      int frameIndex = frames.size();
      // 기준 프레임을 복사한 뒤 실제 송신본에만 Test B/C/D 비트 오류를 주입한다.
      int mode = mode(options.testType(), frameIndex, totalFrames, options.syncDamageInterval());
      if (mode != 0) {
        List<Integer> bitPositions =
            inject(encoded, mode, options.errorCount(), options.errorSeed(), frameIndex);
        injections.add(new InjectionDetail(frameIndex, injectionModeName(mode), bitPositions));
        injected++;
      }
      referenceFrames.add(new Frame(time[0], time[1], time[2], reference));
      frames.add(new Frame(time[0], time[1], time[2], encoded));
      advance(time);
    }
    if (options.testType() == TestType.TEST_D_SYNC_RECOVERY && frames.size() < 2) {
      throw new IllegalArgumentException("Test D requires at least two frames");
    }
    return new Prepared(referenceFrames, frames, injected, records.size(), injections);
  }

  private static int mode(TestType type, int index, int total, int interval) {
    if (type == TestType.TEST_B_RANDOM_ERRORS) return 1;
    if (type == TestType.TEST_C_BURST_ERRORS) return 2;
    return type == TestType.TEST_D_SYNC_RECOVERY && index < total - 1 && index % interval == 0
        ? 3
        : 0;
  }

  /** 오류를 실제로 적용하고 0부터 시작하는 AFS frame 비트 위치를 반환한다. */
  private static List<Integer> inject(byte[] frame, int mode, int count, int seed, int trial) {
    int start = mode == 3 ? 0 : 120, end = mode == 3 ? 68 : 6000;
    if (count < 1 || count > end - start) throw new IllegalArgumentException("Invalid error count");
    DotNetRandom random = new DotNetRandom(seed * 397 ^ trial);
    Set<Integer> indices = new TreeSet<>();
    if (mode == 2) {
      int first = random.next(start, end - count + 1);
      for (int i = 0; i < count; i++) indices.add(first + i);
    } else while (indices.size() < count) indices.add(random.next(start, end));
    for (int bit : indices) frame[bit >>> 3] ^= (byte) (1 << (7 - (bit & 7)));
    return List.copyOf(indices);
  }

  private static String injectionModeName(int mode) {
    return switch (mode) {
      case 1 -> "RANDOM_BIT_ERROR";
      case 2 -> "BURST_BIT_ERROR";
      case 3 -> "SYNC_DAMAGE";
      default -> "NONE";
    };
  }

  private static int[] timeFrom(List<byte[]> records) {
    // 실제 GNSS epoch가 있으면 그 GPS 시간을 우선 사용하고, 메타데이터뿐이면 수집 UTC에서 계산한다.
    for (byte[] record : records) {
      var envelope = GrawCodec.decode(record);
      if (envelope.message() instanceof GrawCodec.ObservationEpoch x)
        return nextTime(x.week(), x.receiverTowSeconds());
    }
    Instant captured = GrawCodec.decode(records.getFirst()).capturedAt();
    long gpsSeconds =
        Duration.between(Instant.parse("1980-01-06T00:00:00Z"), captured).getSeconds() + 18;
    return nextTime((int) (gpsSeconds / 604800), gpsSeconds % 604800);
  }

  private static int[] nextTime(int week, double seconds) {
    int interval = (int) (seconds / 1200);
    int toi = (int) (seconds % 1200) / 12 + 1;
    if (toi >= 100) {
      toi = 0;
      if (++interval >= 504) {
        interval = 0;
        week++;
      }
    }
    return new int[] {week, interval, toi};
  }

  private static void advance(int[] time) {
    if (++time[2] < 100) return;
    time[2] = 0;
    if (++time[1] < 504) return;
    time[1] = 0;
    time[0]++;
  }
}
