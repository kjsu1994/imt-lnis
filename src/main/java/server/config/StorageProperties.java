package server.config;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

/** H2와 파일 저장소가 공유하는 데이터 경로 및 자동 정리 기간 설정이다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "lnis.storage")
public class StorageProperties {
    private Path dataDirectory = Path.of("./data");
    private Duration cleanupDelay = Duration.ofMinutes(10);
}
