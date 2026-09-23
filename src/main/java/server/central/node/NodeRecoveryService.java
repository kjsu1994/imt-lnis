package server.central.node;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import server.central.dtn.DtnJob;
import server.central.dtn.DtnRepository;

import java.time.Instant;
import java.util.List;

/** 재시작으로 사라진 메모리 작업을 READY로 오인하거나 같은 시험을 자동 재송신하지 않는다. */
@Component
@Profile("node")
@RequiredArgsConstructor
public class NodeRecoveryService {
    private final DtnRepository dtnRepository;

    @EventListener(ApplicationStartedEvent.class)
    public void recover()
    {
        for (DtnJob job : dtnRepository.findByStateIn(List.of("PREPARING", "CALCULATING"))) {
            job.setState("FAILED");
            job.setMessage("노드 재시작으로 진행 중 계산이 중단되었습니다. 새 시험으로 다시 시작하세요.");
            job.setUpdatedAt(Instant.now());
            dtnRepository.save(job);
        }
        // WAITING_DTN/WAITING_RECEIVER는 영속 본문/등록 정보로 기존 대기를 계속한다.
    }
}
