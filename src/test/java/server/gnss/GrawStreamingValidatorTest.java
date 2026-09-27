package server.gnss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** 청크 경계에 걸친 GRAW 레코드 검증과 SHA-256 계산의 회귀를 확인한다. */
class GrawStreamingValidatorTest {
    @Test
    void validatesRecordsAcrossChunks() {
        byte[] record =
                GrawCodec.encode(
                        new GrawCodec.Envelope(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                0,
                                Instant.now(),
                                new GrawCodec.ReceiverMetadata(
                                        "F9P", "1", "COM3", 115200, "test")));
        byte[] file =
                ByteBuffer.allocate(record.length + 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .putInt(record.length)
                        .put(record)
                        .array();
        var validator = new GrawStreamingValidator();
        validator.push(Arrays.copyOf(file, 5));
        validator.push(Arrays.copyOfRange(file, 5, file.length));
        var result = validator.finish();
        assertEquals(1, result.records());
        assertEquals(file.length, result.size());
        assertEquals(64, result.sha256().length());
    }
}
