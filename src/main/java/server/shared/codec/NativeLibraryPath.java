package server.shared.codec;

import java.nio.file.Path;
import java.util.Locale;

/** AFS와 PVT가 반드시 같은 운영체제용 라이브러리를 사용하도록 경로 선택을 통일한다. */
public final class NativeLibraryPath {
    private NativeLibraryPath()
    {
    }

    public static Path resolve(Path directory)
    {
        return resolve(directory, System.getProperty("os.name", ""));
    }

    static Path resolve(Path directory, String operatingSystem)
    {
        String normalizedName = operatingSystem.toLowerCase(Locale.ROOT);
        String libraryName;
        if (normalizedName.startsWith("windows")) {
            libraryName = "LnisAfsCodec.dll";
        } else if (normalizedName.equals("linux")) {
            libraryName = "libLnisAfsCodec.so";
        } else {
            // 지원하지 않는 OS에서 다른 형식의 바이너리를 잘못 로드하지 않는다.
            throw new IllegalStateException("지원하지 않는 네이티브 운영체제: " + operatingSystem);
        }
        return directory.resolve(libraryName).toAbsolutePath().normalize();
    }
}
