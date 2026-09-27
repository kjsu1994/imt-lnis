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
