package server.agent.codec;
import server.shared.codec.NativeLibraryPath;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JNA를 통해 기존 C AFS 코덱 ABI를 안전하게 호출한다.
 *
 * <p>Java 배열은 C 함수가 요구하는 정확한 SB bit 수인지 확인하고, bit 값도 0/1만 허용한다. C 코덱이 전역 오류 상태를 사용하므로
 * encode/decode와 오류 문자열 조회를 하나의 lock 구간으로 보호한다.
 */
public final class NativeAfsCodec implements AutoCloseable {
  public static final int FRAME_BYTES = 750;
  private static final ReentrantLock GATE = new ReentrantLock();
  private final Api api;

  public interface Api extends Library {
    int lnis_afs_get_abi_version();

    Pointer lnis_afs_get_last_error();

    int lnis_afs_encode_frame(
        byte toi,
        byte[] sb2,
        int sb2Len,
        byte[] sb3,
        int sb3Len,
        byte[] sb4,
        int sb4Len,
        byte[] frame,
        int frameLen);

    int lnis_afs_decode_frame(
        byte toi,
        byte[] frame,
        int frameLen,
        byte[] sb2,
        int sb2Len,
        byte[] sb3,
        int sb3Len,
        byte[] sb4,
        int sb4Len,
        DecodeStatus status);
  }

  @Structure.FieldOrder({
    "sb2Ok",
    "sb3Ok",
    "sb4Ok",
    "sb2Corrections",
    "sb3Corrections",
    "sb4Corrections"
  })
  public static class DecodeStatus extends Structure {
    public byte sb2Ok, sb3Ok, sb4Ok;
    public int sb2Corrections, sb3Corrections, sb4Corrections;
  }

  /** Native Decoder가 반환한 서브블록 원문과 블록별 CRC·내부 판정 정보를 묶는다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Decoded {
    /** 복호화된 SB2의 0/1 bit 배열이며 길이는 1,176이다. */
    byte[] sb2;

    /** 복호화된 SB3의 0/1 bit 배열이며 길이는 846이다. */
    byte[] sb3;

    /** 복호화된 SB4의 0/1 bit 배열이며 길이는 846이다. */
    byte[] sb4;

    /** SB2 데이터에 포함된 CRC 검사 결과다. */
    boolean sb2Valid;

    /** SB3 데이터에 포함된 CRC 검사 결과다. */
    boolean sb3Valid;

    /** SB4 데이터에 포함된 CRC 검사 결과다. */
    boolean sb4Valid;

    /** SB2 LDPC 처리 중 내부 판정이 변경된 횟수이며 실제 주입 오류 수가 아니다. */
    int sb2Corrections;

    /** SB3 LDPC 처리 중 내부 판정이 변경된 횟수다. */
    int sb3Corrections;

    /** SB4 LDPC 처리 중 내부 판정이 변경된 횟수다. */
    int sb4Corrections;
  }

  private NativeAfsCodec(Api api) {
    this.api = api;
    if (api.lnis_afs_get_abi_version() != 1)
      throw new IllegalStateException("Unsupported LnisAfsCodec ABI");
  }

  public static NativeAfsCodec load(Path directory) {
    Path library = NativeLibraryPath.resolve(directory);
    return new NativeAfsCodec(Native.load(library.toString(), Api.class));
  }

  public int abiVersion() {
    return api.lnis_afs_get_abi_version();
  }

  /** SB2/SB3/SB4 bit 배열을 750 byte AFS frame으로 부호화한다. */
  public byte[] encode(int toi, byte[] sb2, byte[] sb3, byte[] sb4) {
    require(toi, sb2, sb3, sb4);
    byte[] frame = new byte[FRAME_BYTES];
    GATE.lock();
    try {
      check(
          "encode",
          api.lnis_afs_encode_frame(
              (byte) toi, sb2, sb2.length, sb3, sb3.length, sb4, sb4.length, frame, frame.length));
      return frame;
    } finally {
      GATE.unlock();
    }
  }

  /** 750 byte frame을 세 sub-block과 CRC/보정 상태로 복호화한다. */
  public Decoded decode(int toi, byte[] frame) {
    if (toi < 0 || toi > 99 || frame.length != FRAME_BYTES)
      throw new IllegalArgumentException("Invalid AFS frame or TOI");
    byte[] sb2 = new byte[1176], sb3 = new byte[846], sb4 = new byte[846];
    DecodeStatus status = new DecodeStatus();
    GATE.lock();
    try {
      int result =
          api.lnis_afs_decode_frame(
              (byte) toi,
              frame,
              frame.length,
              sb2,
              sb2.length,
              sb3,
              sb3.length,
              sb4,
              sb4.length,
              status);
      if (result < 0) check("decode", result);
      status.read();
      return new Decoded(
          sb2,
          sb3,
          sb4,
          status.sb2Ok != 0,
          status.sb3Ok != 0,
          status.sb4Ok != 0,
          status.sb2Corrections,
          status.sb3Corrections,
          status.sb4Corrections);
    } finally {
      GATE.unlock();
    }
  }

  private void check(String operation, int code) {
    if (code == 0) return;
    Pointer pointer = api.lnis_afs_get_last_error();
    String detail = pointer == null ? "unknown" : pointer.getString(0, "UTF-8");
    throw new IllegalStateException(
        "AFS native " + operation + " failed (" + code + "): " + detail);
  }

  private static void require(int toi, byte[] sb2, byte[] sb3, byte[] sb4) {
    if (toi < 0 || toi > 99 || sb2.length != 1176 || sb3.length != 846 || sb4.length != 846) {
      throw new IllegalArgumentException("Invalid AFS block length or TOI");
    }
    for (byte[] block : List.of(sb2, sb3, sb4)) {
      for (byte value : block) {
        if (value != 0 && value != 1) {
          throw new IllegalArgumentException("AFS inputs must be bits");
        }
      }
    }
  }

  @Override
  public void close() {}
}
