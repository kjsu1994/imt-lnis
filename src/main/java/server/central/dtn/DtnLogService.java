package server.central.dtn;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;
import server.shared.model.DtnModels.Pvt;

@Service @RequiredArgsConstructor
public class DtnLogService {
    private final DtnLogRepository repository;
    private final Map<UUID,String> captureStages = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,Long> captureTimes = new java.util.concurrent.ConcurrentHashMap<>();
    public boolean hasStage(UUID id, String stage) { return repository.existsByScopeIdAndStage(id,stage); }
    public void capture(UUID id,String stage,String message) {
        long now=System.nanoTime();
        if(!Objects.equals(captureStages.put(id,stage),stage) || now-captureTimes.getOrDefault(id,0L)>10_000_000_000L) {
            captureTimes.put(id,now); add(id,"INPUT","COM 수집","Capturing".equals(stage),message);
        }
    }

    public boolean exists(UUID id) { return id != null && repository.existsByScopeId(id); }
    public List<DtnLogEntry> read(UUID id, long after) {
        if (id == null || after < 0) throw new IllegalArgumentException("로그 식별자·순번을 확인하세요.");
        return repository.findByScopeIdAndSequenceGreaterThanOrderBySequence(id, after, PageRequest.of(0, 500));
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void add(UUID id, String type, String stage, boolean detail, String message) {
        add(id, type, "INFO", stage, detail, message);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void add(UUID id, String type, String level, String stage, boolean detail, String message) {
        if (id == null) return;
        repository.saveAndFlush(new DtnLogEntry(id,type,Instant.now(),level,stage,detail,clean(message)));
    }
    private static String clean(String message) {
        String value = String.valueOf(message).replaceAll("[\\r\\n\\t]", " ")
            .replaceAll("(?i)Bearer\\s+[^\\s]+", "Bearer [숨김]")
            .replaceAll("(?i)(https?://)[^/\\s]+@", "$1[숨김]@");
        return value.substring(0,Math.min(value.length(),2000));
    }
    /** Snapshot preparation history so input expiry and reuse cannot change a past trial. */
    @Transactional
    public void copy(UUID from, UUID to, String type) {
        if (from == null || from.equals(to)) return;
        long cursor=0;
        while (true) {
            var entries=read(from,cursor);
            for (var e:entries) {
                repository.save(new DtnLogEntry(to,type,e.getOccurredAt(),e.getLevel(),e.getStage(),e.isDetail(),e.getMessage()));
                cursor=e.getSequence();
            }
            if(entries.size()<500) break;
        }
    }
    public void pvt(UUID id, String type, List<Pvt> values, long started) {
        if (values == null || values.isEmpty()) {
            add(id,type,"WARN","PVT",false,"계산 가능한 관측 시점이 없습니다. 관측값·항법정보를 확인하세요."); return;
        }
        for (Pvt p:values) {
            boolean valid=p.isPositionValid() && p.isVelocityValid();
            add(id,type,valid?"INFO":"WARN","PVT",false,
                (valid?"지구 PVT 계산 완료":"지구 PVT 계산 결과 · 유효 해 부족") + " · Week " + p.getWeek()
                + " / TOW " + p.getTowSeconds() + " s · 위치 " + (p.isPositionValid()?"유효":"무효") + " · 속도 " + (p.isVelocityValid()?"유효":"무효")
                + " · 사용 위성 " + p.getSatellitesUsed() + " · " + (p.getMessage()==null?"":p.getMessage()));
            if(valid) add(id,type,"PVT",true,"ECEF(m) " + Arrays.toString(p.getEcefMeters())
                + " · 속도(m/s) " + Arrays.toString(p.getVelocityMetersPerSecond()) + " · 시계오차(s) " + p.getReceiverClockBiasSeconds());
        }
        if(started>0) add(id,type,"PVT",true,"계산 소요 " + ((System.nanoTime()-started)/1_000_000) + " ms");
    }
    @Scheduled(fixedDelay = 600000) @Transactional
    public void cleanup() {
        repository.deleteByScopeTypeNotAndOccurredAtBefore("TEST",Instant.now().minus(Duration.ofDays(7)));
        captureStages.keySet().removeIf(id->!exists(id)); captureTimes.keySet().retainAll(captureStages.keySet());
    }
}
