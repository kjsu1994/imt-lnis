package server.afs;

import server.common.Hashing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * GRAW 레코드와 AFS SB3/SB4용 fragment 사이의 binary 변환을 담당한다.
 *
 * <p>각 fragment에 record sequence, fragment index/count, 원본 길이와 CRC32를 포함해 전송 순서 검증 후 원래
 * record를 검증할 수 있게 한다. SB payload 크기를 넘는 레코드는 여러 fragment로 나눈다.
 */
public final class AfsRawFragmentCodec {
    public static final int BLOCK_BYTES = 105, HEADER_BYTES = 19, PAYLOAD_BYTES = 86;

    private AfsRawFragmentCodec() {}

    /** 하나의 GRAW 레코드를 SB3 또는 SB4에 실을 수 있게 분할한 105 byte 블록의 해석 결과다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class Fragment {
        /** 원본 GRAW 레코드의 0부터 시작하는 순번이다. */
        long recordSequence;

        /** 같은 레코드 안에서 이 조각의 0부터 시작하는 번호다. */
        int fragmentIndex;

        /** 원본 레코드를 완성하는 데 필요한 전체 조각 개수다. */
        int fragmentCount;

        /** 조각으로 나누기 전 원본 GRAW 레코드 크기이며 단위는 byte다. */
        long recordLength;

        /** 이 조각의 {@link #payload()}에 실제로 사용된 크기이며 최대 86 byte다. */
        int payloadLength;

        /** 모든 조각이 공유하는 원본 GRAW 레코드의 unsigned CRC32 값이다. */
        long recordCrc32;

        /** 현재 조각에 담긴 원본 GRAW 데이터 부분이다. */
        byte[] payload;
    }

    public static List<byte[]> fragment(long sequence, byte[] record) {
        if (record.length == 0) {
            throw new IllegalArgumentException("Empty GRAW record");
        }
        int count = (record.length + PAYLOAD_BYTES - 1) / PAYLOAD_BYTES;
        if (count > 65535) {
            throw new IllegalArgumentException("Too many fragments");
        }
        long crc = Hashing.crc32(record);
        List<byte[]> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int offset = i * PAYLOAD_BYTES,
                    length = Math.min(PAYLOAD_BYTES, record.length - offset);
            ByteBuffer out = ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.BIG_ENDIAN);
            out.put((byte) 1)
                    .put((byte) ((i == 0 ? 1 : 0) | (i == count - 1 ? 2 : 0)))
                    .putInt((int) sequence)
                    .putShort((short) i)
                    .putShort((short) count)
                    .putInt(record.length)
                    .put((byte) length)
                    .putInt((int) crc)
                    .put(record, offset, length);
            result.add(out.array());
        }
        return result;
    }

    public static Fragment decode(byte[] block) {
        if (block.length != BLOCK_BYTES || block[0] != 1) {
            throw new IllegalArgumentException("Invalid AFS custom block");
        }
        ByteBuffer in = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
        in.position(2);
        long sequence = Integer.toUnsignedLong(in.getInt());
        int index = Short.toUnsignedInt(in.getShort());
        int count = Short.toUnsignedInt(in.getShort());
        long recordLength = Integer.toUnsignedLong(in.getInt());
        int length = Byte.toUnsignedInt(in.get());
        long crc = Integer.toUnsignedLong(in.getInt());
        if (count == 0 || index >= count || length > PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid AFS fragment metadata");
        }
        byte[] payload = new byte[length];
        in.get(payload);
        return new Fragment(sequence, index, count, recordLength, length, crc, payload);
    }

    public static byte[] toSbBits(byte[] block) {
        if (block.length != BLOCK_BYTES) {
            throw new IllegalArgumentException("AFS block must be 105 bytes");
        }
        byte[] bits = new byte[846];
        int messageType = 63;
        for (int i = 0; i < 6; i++) {
            bits[i] = (byte) ((messageType >> (5 - i)) & 1);
        }
        for (int i = 0; i < block.length * 8; i++) {
            bits[6 + i] = (byte) ((block[i >>> 3] >> (7 - (i & 7))) & 1);
        }
        return bits;
    }

    public static byte[] fromSbBits(byte[] bits) {
        if (bits.length != 846) {
            throw new IllegalArgumentException("SB must have 846 bits");
        }
        int type = 0;
        for (int i = 0; i < 6; i++) {
            type = (type << 1) | bits[i];
        }
        if (type != 63) {
            throw new IllegalArgumentException("Unexpected custom message type");
        }
        byte[] block = new byte[BLOCK_BYTES];
        for (int i = 0; i < block.length * 8; i++) {
            block[i >>> 3] |= (byte) (bits[6 + i] << (7 - (i & 7)));
        }
        return block;
    }
}
