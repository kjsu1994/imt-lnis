package server.shared.codec;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 호스트 OS와 무관하게 Windows/Linux 경로 선택 계약을 검증한다. */
class NativeLibraryPathTest {
    @Test
    void selectsWindowsDll()
    {
        Path library = NativeLibraryPath.resolve(Path.of("native"), "Windows 11");
        assertEquals("LnisAfsCodec.dll", library.getFileName().toString());
    }

    @Test
    void selectsLinuxSharedLibrary()
    {
        Path library = NativeLibraryPath.resolve(Path.of("native"), "Linux");
        assertEquals("libLnisAfsCodec.so", library.getFileName().toString());
    }

    @Test
    void rejectsUnsupportedPlatformInsteadOfMistakingDarwinForWindows()
    {
        assertThrows(IllegalStateException.class,
                () -> NativeLibraryPath.resolve(Path.of("native"), "Darwin"));
    }
}
