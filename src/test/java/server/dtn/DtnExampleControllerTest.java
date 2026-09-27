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
