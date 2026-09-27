package server.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 시험 입력과 복원 결과의 CRC32 및 SHA-256 계산을 제공한다.
 *
 * <p>CRC32는 개별 wire record의 전송 오류 검출에 사용하고, SHA-256은 전체 원본 GRAW와 Receiver 복원 stream이 byte 단위로 동일한지
 * 최종 판정하는 데 사용한다.
 */
public final class Hashing {
    private Hashing() {}

    public static long crc32(byte[] data) {
        return crc32(data, 0, data.length);
    }

    public static long crc32(byte[] data, int offset, int length) {
        long crc = 0xffff_ffffL;
        for (int p = offset; p < offset + length; p++) {
            crc ^= data[p] & 0xffL;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc >>> 1) ^ (0xedb8_8320L & -(crc & 1));
            }
        }
        return (~crc) & 0xffff_ffffL;
    }

    public static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] value) {
        return HexFormat.of().withUpperCase().formatHex(value);
    }
}
