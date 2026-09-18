package server.central.config;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import server.central.realtime.RealtimeEventRepository;
import java.time.Duration;
import java.time.Instant;

/** 내부 통신 이벤트만 단기 보관한다. 시험·입력은 사용자 보관 설정으로 관리한다. */
@RequiredArgsConstructor @Service
public class StorageCleanupService {
    private final RealtimeEventRepository realtimeEventRepository;
    @Scheduled(fixedDelayString="${lnis.storage.cleanup-delay:PT10M}")
    @Transactional
    public void cleanup() {
        realtimeEventRepository.deleteExpiredStreams(Instant.now().minus(Duration.ofHours(24)));
    }
}
