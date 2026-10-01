package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import server.gnss.InputBufferEntity;
import server.gnss.InputBufferService;
import server.pvt.DtnPvtCalculator;

import java.util.UUID;

class DtnInputViewTest {
    @Test
    void senderObservationViewClassifiesNavigationWithoutRewritingRawRecords() {
        var inputs = mock(InputBufferService.class);
        var entity = mock(InputBufferEntity.class);
        UUID id = UUID.randomUUID();
        var words = java.util.List.of(0x8bL << 22, 1L << 8, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        byte[] record = server.gnss.GrawCodec.encode(new server.gnss.GrawCodec.Envelope(
                id, UUID.randomUUID(), 17, java.time.Instant.parse("2026-10-01T00:00:00Z"),
                new server.gnss.GrawCodec.NavigationUpdate(0, 4, 0, 0, 2, words)));
        byte[] raw = java.nio.ByteBuffer.allocate(record.length + 4).putInt(record.length).put(record).array();
        when(inputs.get(id)).thenReturn(entity);
        when(entity.complete()).thenReturn(true);
        when(entity.receivedSize()).thenReturn((long) raw.length);
        when(inputs.readChunks(entity, server.common.DtnModels.MAX_INPUT_BYTES)).thenReturn(raw);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var controller = new DtnInputViewController(inputs, json);
        var result = controller.observations(id);
        assertEquals(1, result.path("navigation").get(0).path("display").path("subframeId").asInt());
        assertEquals(17, result.path("navigation").get(0).path("sequence").asInt());
        assertEquals(json.valueToTree(words), result.path("navigation").get(0).path("message").path("words"));
        assertFalse(result.path("records").get(0).has("display"));
        assertEquals(1, result.path("navigationCount").asInt());
        verify(inputs).get(id);
        verify(inputs).readChunks(entity, server.common.DtnModels.MAX_INPUT_BYTES);
        verifyNoMoreInteractions(inputs);
    }

    @Test
    void refusesIncompleteAndOversizedInputsBeforeReadingChunks() {
        var inputs = mock(InputBufferService.class);
        var entity = mock(InputBufferEntity.class);
        UUID id = UUID.randomUUID();
        when(inputs.get(id)).thenReturn(entity);
        when(entity.inputId()).thenReturn(id);
        when(inputs.readChunks(any(), anyInt())).thenCallRealMethod();
        var controller =
                new DtnInputViewController(
                        inputs, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> controller.observations(id));
        when(entity.complete()).thenReturn(true);
        when(entity.receivedSize()).thenReturn(1_048_577L);
        assertThrows(IllegalArgumentException.class, () -> controller.observations(id));
        verify(inputs, never()).chunk(any(), anyLong());
    }

    @Test
    void uploadedAndLegacyInputsStillUseNodeCalculator() throws Exception {
        var inputs = mock(InputBufferService.class);
        var entity = mock(InputBufferEntity.class);
        UUID id = UUID.randomUUID();
        byte[] record =
                server.gnss.GrawCodec.encode(
                        new server.gnss.GrawCodec.Envelope(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                0,
                                java.time.Instant.now(),
                                new server.gnss.GrawCodec.ObservationEpoch(
                                        1, 2400, 18, 1, 1, java.util.List.of())));
        byte[] raw =
                java.nio.ByteBuffer.allocate(record.length + 4)
                        .putInt(record.length)
                        .put(record)
                        .array();
        when(inputs.get(id)).thenReturn(entity);
        when(entity.inputId()).thenReturn(id);
        when(inputs.readChunks(any(), anyInt())).thenCallRealMethod();
        when(entity.complete()).thenReturn(true);
        when(entity.receivedSize()).thenReturn((long) raw.length);
        when(entity.chunkCount()).thenReturn(1L);
        when(inputs.chunk(id, 0)).thenReturn(raw);
        var controller =
                new DtnInputViewController(
                        inputs, new com.fasterxml.jackson.databind.ObjectMapper());
        var calculator = mock(DtnPvtCalculator.class);
        var result = java.util.List.of(new server.common.DtnModels.Pvt());
        when(calculator.calculate(any())).thenReturn(result);
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "calculator", calculator);
        assertSame(result, controller.pvt(id));
        verify(calculator)
                .calculate(
                        argThat(
                                records ->
                                        records.size() == 1
                                                && java.util.Arrays.equals(
                                                        record, records.getFirst())));
        when(entity.complete()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> controller.pvt(id));
        verifyNoMoreInteractions(calculator);
    }
}
