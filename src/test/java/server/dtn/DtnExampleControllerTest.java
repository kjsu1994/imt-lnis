package server.dtn;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;

class DtnExampleControllerTest {
    @TempDir Path directory;

    @Test
    void replayPreservesAllTenRealEpochsWithoutSyntheticFallback() throws Exception {
        var controller = new DtnExampleController();
        var inputs = org.mockito.Mockito.mock(server.gnss.InputBufferService.class);
        var input = org.mockito.Mockito.mock(server.gnss.InputBufferEntity.class);
        var id = java.util.UUID.randomUUID();
        byte[] bytes = Files.readAllBytes(Path.of("real-gnss-10epochs.graw"));
        org.mockito.Mockito.when(input.inputId()).thenReturn(id);
        org.mockito.Mockito.when(inputs.create("real-gnss-10epochs.graw", bytes.length,
                server.common.LnisModels.InputKind.GRAW_UPLOAD)).thenReturn(input);
        org.mockito.Mockito.when(inputs.complete(id)).thenReturn(input);
        ReflectionTestUtils.setField(controller, "inputs", inputs);
        ReflectionTestUtils.setField(controller, "realFile", "real-gnss-10epochs.graw");
        ReflectionTestUtils.setField(controller, "syntheticFile", "must-not-be-opened.graw");
        assertSame(input, controller.replay());
        org.mockito.Mockito.verify(inputs).append(org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.AdditionalMatchers.aryEq(bytes));
        ReflectionTestUtils.setField(controller, "realFile", "");
        assertThrows(ResponseStatusException.class, controller::replay);
    }

    @Test
    void rejectsUnconfiguredMissingAndMalformedExamples() throws Exception {
        var controller = new DtnExampleController();
        ReflectionTestUtils.setField(controller, "file", "");
        assertThrows(ResponseStatusException.class, controller::file);
        Path path = directory.resolve("example.graw");
        ReflectionTestUtils.setField(controller, "file", path.toString());
        assertThrows(ResponseStatusException.class, controller::file);
        Files.write(path, new byte[] {1, 2, 3});
        assertThrows(IllegalArgumentException.class, controller::file);
    }
}
