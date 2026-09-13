package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import server.central.input.InputBufferEntity;
import server.central.input.InputBufferService;

class DtnInputViewTest {
  @Test void refusesIncompleteAndOversizedInputsBeforeReadingChunks() {
    var inputs = mock(InputBufferService.class);
    var entity = mock(InputBufferEntity.class);
    UUID id = UUID.randomUUID();
    when(inputs.get(id)).thenReturn(entity);
    var controller = new DtnInputViewController(inputs);
    assertThrows(IllegalArgumentException.class, () -> controller.observations(id));
    when(entity.complete()).thenReturn(true);
    when(entity.receivedSize()).thenReturn(1_048_577L);
    assertThrows(IllegalArgumentException.class, () -> controller.observations(id));
    verify(inputs, never()).chunk(any(), anyLong());
  }
}
