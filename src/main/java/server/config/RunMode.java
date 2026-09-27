package server.config;

import java.util.Arrays;
import java.util.Locale;

/** 실행 명령의 첫 인수를 Spring profile과 Web 모드로 변환한다. */
public enum RunMode {
    NODE;

    public String profile() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 노드도 서버의 웹/JPA 설정을 사용하지만 별도 Agent profile은 활성화하지 않는다. */
    public String[] profiles() {
        return new String[] {"server", "node"};
    }

    public boolean webEnabled() {
        return true;
    }

    public static Selection select(String[] arguments) {
        if (arguments.length == 0 || arguments[0].startsWith("--")) {
            throw new IllegalArgumentException("통합 노드로 실행하세요: java -jar lnis.jar node [Spring 옵션]");
        }
        try {
            RunMode mode = valueOf(arguments[0].toUpperCase(Locale.ROOT));
            return new Selection(mode, Arrays.copyOfRange(arguments, 1, arguments.length));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "지원하지 않는 실행 모드입니다: " + arguments[0] + " (통합 node 모드만 지원)", error);
        }
    }

    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.experimental.Accessors(fluent = true)
    public static class Selection {
        RunMode mode;
        String[] springArguments;
    }
}
