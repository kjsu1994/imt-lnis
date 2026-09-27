package server.gnss;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import server.common.Hashing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

/** 청크 경계에 걸친 length-prefixed GRAW 레코드를 스트리밍 방식으로 검증한다. */
final class GrawStreamingValidator {
    private final MessageDigest sha = Hashing.sha256Digest();
    private byte[] pending = new byte[0];
    private long size;
    private long records;

    void push(byte[] chunk) {
        sha.update(chunk);
        size += chunk.length;
        byte[] joined = Arrays.copyOf(pending, pending.length + chunk.length);
        System.arraycopy(chunk, 0, joined, pending.length, chunk.length);
        pending = joined;
        int offset = 0;
        while (pending.length - offset >= 4) {
            int length = ByteBuffer.wrap(pending, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            if (length <= 0 || length > GrawCodec.MAXIMUM_RECORD_LENGTH) {
                throw new IllegalArgumentException("Invalid capture.graw record length " + length);
            }
            if (pending.length - offset - 4 < length) {
                break;
            }
            GrawCodec.decode(Arrays.copyOfRange(pending, offset + 4, offset + 4 + length));
            records++;
            offset += 4 + length;
        }
        if (offset > 0) {
            pending = Arrays.copyOfRange(pending, offset, pending.length);
        }
    }

    Result finish() {
        if (size == 0 || records == 0 || pending.length != 0) {
            throw new IllegalArgumentException("capture.graw is empty or truncated");
        }
        return new Result(size, records, Hashing.hex(sha.digest()));
    }

    /** 입력 청크 전체를 순서대로 검증한 뒤 완료 처리에 전달하는 결과다. */
    @Value
    @AllArgsConstructor
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY)
    static class Result {
        /** 검증한 전체 입력 크기이며 단위는 byte다. */
        long size;

        /** 길이-prefix, 내부 구조와 CRC 검사를 통과한 GRAW 레코드 수다. */
        long records;

        /** 모든 입력 청크를 연결한 전체 바이트의 SHA-256이다. */
        String sha256;
    }
}
