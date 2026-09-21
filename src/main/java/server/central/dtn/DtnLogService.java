package server.central.dtn;

import server.shared.codec.DtnDelay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;
import server.shared.model.DtnModels.Pvt;

@Service @RequiredArgsConstructor @Slf4j
public class DtnLogService {
    private final DtnLogRepository repository;
    private final Map<UUID,String> captureStages = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,Long> captureTimes = new java.util.concurrent.ConcurrentHashMap<>();

    private static String clean(String message) {
        String value = String.valueOf(message).replaceAll("[\\r\\n\\t]", " ")
            .replaceAll("(?i)Bearer\\s+[^\\s]+", "Bearer [숨김]")
            .replaceAll("(?i)(https?://)[^/\\s]+@", "$1[숨김]@");
        return value.substring(0,Math.min(value.length(),2000));
    }

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
        var entry=new DtnLogEntry(id,type,Instant.now(),level,stage,detail,clean(message));
        repository.saveAndFlush(entry);
        console(entry);
    }

    /** 브라우저 전용 알림도 같은 노드의 콘솔에서 확인한다. DB 이력에는 중복 삽입하지 않는다. */
    public void screen(UUID id, Instant occurredAt, String level, String message) {
        console(new DtnLogEntry(id,"SCREEN",occurredAt,level,"화면",false,clean(message)));
    }

    private void console(DtnLogEntry entry) {
        String format="DTN_EVENT type={} scopeId={} sequence={} occurredAt={} [{}] detail={} {}\n";
        Object[] values={entry.getScopeType(),entry.getScopeId(),entry.getSequence(),entry.getOccurredAt(),clean(entry.getStage()),entry.isDetail(),entry.getMessage()};
        switch(entry.getLevel()) {
            case "ERROR" -> log.error(format,values);
            case "WARN" -> log.warn(format,values);
            default -> log.info(format,values);
        }
    }

    /** 수신 노드에 저장된 어댑터 부가 로그를 조회한다. 송신 노드에는 복제하지 않는다. */
    public List<DtnRemoteResult.AdapterLog> adapterEntries(UUID id) {
        return repository.findByScopeIdAndStageOrderBySequence(id,"DTN").stream()
            .map(e -> new DtnRemoteResult.AdapterLog(e.getOccurredAt(),e.getLevel(),e.getMessage())).toList();
    }

    @Transactional
    public void importAdapter(UUID id, List<DtnRemoteResult.AdapterLog> entries) {
        if (entries == null || entries.isEmpty() || hasStage(id,"DTN")) return;
        for (var e:entries.stream().limit(501).toList()) {
            if(e.occurredAt()==null || e.message()==null) continue;
            String level=List.of("INFO","WARN","ERROR").contains(e.level())?e.level():"INFO";
            var entry=new DtnLogEntry(id,"TEST",e.occurredAt(),level,"DTN",true,clean(e.message()));
            repository.save(entry);
            console(entry);
        }
    }

    @Transactional
    public void adapter(UUID id, com.fasterxml.jackson.databind.JsonNode encoded, Instant receivedAt) {
        adapter(id,encoded,null,receivedAt);
    }

    @Transactional
    public void adapter(UUID id, com.fasterxml.jackson.databind.JsonNode encoded,
            com.fasterxml.jackson.databind.JsonNode plain, Instant receivedAt) {
        if (hasStage(id,"DTN")) return;
        var entries=new ArrayList<DtnRemoteResult.AdapterLog>();
        for(var field:new com.fasterxml.jackson.databind.JsonNode[]{encoded,plain}) {
            if(field==null) continue;
            try {
                if(!field.isTextual() || field.textValue().length()>131072) throw new IllegalArgumentException();
                String text=field.textValue();
                if(field==encoded) text=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(text))).toString();
                entries.addAll(parseAdapter(text,receivedAt));
            } catch(IllegalArgumentException | java.nio.charset.CharacterCodingException error) {
                entries.add(new DtnRemoteResult.AdapterLog(receivedAt,"WARN",
                    "어댑터 로그 해석 실패 · dtnLogs 문자열/dtnLogsBase64 UTF-8 형식 및 크기 확인 · 시험 데이터는 정상 접수"));
            }
        }
        if(entries.size()>501) {
            entries=new ArrayList<>(entries.subList(0,500));
            entries.add(new DtnRemoteResult.AdapterLog(receivedAt,"WARN","어댑터 로그 500줄 초과 · 수신 JSON 원문 참조"));
        }
        importAdapter(id,entries);
    }

    static List<DtnRemoteResult.AdapterLog> parseAdapter(String text, Instant receivedAt) {
        var entries=new ArrayList<DtnRemoteResult.AdapterLog>();
        ZoneId zone=ZoneId.of("Asia/Seoul");
        Instant anchor=receivedAt;
        var pattern=java.util.regex.Pattern.compile("^\\[([^]]+)]\\s*(.*)$");
        String[] lines=text.split("\\R");
        for(String line:lines) {
            if(line.isBlank()) continue;
            if(entries.size()==500) {
                entries.add(new DtnRemoteResult.AdapterLog(receivedAt,"WARN","어댑터 로그 500줄 초과 · 이후 내용은 수신 JSON 원문 참조")); break;
            }
            Instant at=anchor; String message=line;
            var match=pattern.matcher(line);
            var hdtn=java.util.regex.Pattern.compile("\\b[0-9]{4}-[A-Za-z]{3}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\b").matcher(line);
            if(hdtn.find()) {
                try {
                    at=LocalDateTime.parse(hdtn.group(),java.time.format.DateTimeFormatter.ofPattern("uuuu-MMM-dd HH:mm:ss",Locale.ENGLISH))
                        .toInstant(ZoneOffset.UTC);
                    anchor=at;
                } catch(java.time.DateTimeException ignored) { message="[시각 해석 불가] "+line; }
            } else if(match.matches()) {
                try {
                    String stamp=match.group(1);
                    if(stamp.contains("T")) at=OffsetDateTime.parse(stamp).toInstant();
                    else {
                        LocalTime time=LocalTime.parse(stamp);
                        at=anchor.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant();
                        if(at.isAfter(anchor.plusSeconds(43200))) at=at.minusSeconds(86400);
                        if(at.isBefore(anchor.minusSeconds(43200))) at=at.plusSeconds(86400);
                    }
                    anchor=at; message=match.group(2);
                } catch(java.time.DateTimeException ignored) {
                    message="[시각 해석 불가 · 직전 시각/수신 시각 사용] "+line;
                }
            }
            String level=message.matches("(?i).*\\[\\s*(ERROR|FATAL)\\s*].*" )?"ERROR":
                message.matches("(?i).*\\[\\s*WARN(?:ING)?\\s*].*" )?"WARN":"INFO";
            entries.add(new DtnRemoteResult.AdapterLog(at,level,message));
        }
        return entries;
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
        pvt(id,type,values,started,"PVT");
    }
    public void pvt(UUID id, String type, List<Pvt> values, long started, String stage) {
        if (values == null || values.isEmpty()) {
            add(id,type,"WARN",stage,false,"계산 가능한 관측 시점이 없습니다. 관측값·항법정보를 확인하세요.");
            return;
        }
        for (Pvt p:values) {
            boolean valid=p.isPositionValid() && p.isVelocityValid();
            add(id,type,valid?"INFO":"WARN",stage,false,
                (valid?"지구 PVT 계산 완료":"지구 PVT 계산 결과 · 유효 해 부족") + " · Week " + p.getWeek()
                + " / TOW " + p.getTowSeconds() + " s · 위치 " + (p.isPositionValid()?"유효":"무효") + " · 속도 " + (p.isVelocityValid()?"유효":"무효")
                + " · 사용 위성 " + p.getSatellitesUsed() + " · " + (p.getMessage()==null?"":p.getMessage()));
            if(p.isPositionValid()) add(id,type,stage,true,"ECEF(m) " + Arrays.toString(p.getEcefMeters())
                + " · 속도(m/s) " + (p.isVelocityValid() ? Arrays.toString(p.getVelocityMetersPerSecond()) : "계산 불가") + " · 시계오차(s) " + p.getReceiverClockBiasSeconds());
        }
        if(started>0) add(id,type,stage,true,"계산 소요 " + ((System.nanoTime()-started)/1_000_000) + " ms");
    }
    /** 수신 노드에만 위성별 계산 근거를 남긴다. 송신 노드는 최종 요약만 기록한다. */
    public void delay(UUID id, DtnDelay.Evidence evidence)
    {
        if (evidence == null || hasStage(id, "지연 측정")) {
            return;
        }
        add(id, "TEST", "WARN", "지연 측정", false,
                "수신측 · 시험 시작→수신 지연 " + evidence.delaySeconds() * 1000
                        + " ms · 양쪽 PC 시계 동기화 정확도 미확인");
        add(id, "TEST", "지연 측정", true,
                "시작 " + evidence.timing().startedAt()
                        + " · 본문 수신 완료 " + evidence.timing().receivedAt()
                        + " · Δt = 수신 − 시작 = " + evidence.delaySeconds()
                        + " s · c = 299792458 m/s · c×Δt = " + evidence.addedMeters() + " m");
        add(id, "TEST", "관측 시각", true,
                "원본 GNSS 시간축 유지 · GPS 원본 t₀ = " + gpsTime(evidence.originalTime())
                        + " · 계산 t₁ = t₀ + Δt = " + gpsTime(evidence.shiftedTime()));

        add(id, "TEST", "시험 시각 기준", true,
                "S = 송신 서버 시험 시작 접수 · R = 수신 본문 수신 완료 · Δt = R−S"
                        + " · 수집 후 시험 시작 전 대기시간 제외 · 준비·어댑터 처리·전송 포함"
                        + " · 보정 송신 시각 S−Pᵢ/c는 시험 기준 가상 시각이며 실제 위성 송신 시각이 아닙니다.");

        for (var satellite : evidence.satellites()) {
            String identity = "GNSS " + satellite.constellationId()
                    + " / PRN " + satellite.satelliteId() + " / 신호 " + satellite.signalId();
            add(id, "TEST", "송신 시각 역산", true,
                    identity + " · Pᵢ = " + satellite.originalMeters()
                            + " m · Pᵢ/c = " + satellite.propagationSeconds()
                            + " s · t_txᵢ = t₀ − Pᵢ/c = " + gpsTime(satellite.transmitTime()));

            var aligned = DtnDelay.alignedTransmitAt(evidence.timing(), satellite);
            add(id, "TEST", "송신 시각 보정", true,
                    identity + " · t′_txᵢ = S−Pᵢ/c = " + (aligned == null ? "계산 불가" : aligned)
                            + " · R = " + evidence.timing().receivedAt()
                            + " · UTC 시험 기준 가상 시각 · GNSS 계산 시간축과 구분");

            String usage = satellite.solverInput()
                    ? "GPS L1 계산 입력 (최종 채택은 RTKLIB 결정)" : "계산 대상 제외";
            add(id, "TEST", "의사거리 재계산", true,
                    identity + " · P′ᵢ = c×(R−t′_txᵢ) = Pᵢ+c×Δt = " + satellite.recalculatedMeters()
                            + " m · 계산은 절대시각 반올림 영향을 피하도록 Pᵢ+c×Δt 사용 · 변화 " + evidence.addedMeters()
                            + " m · 원본 Doppler " + satellite.dopplerHz()
                            + " Hz / C/N0 " + satellite.cn0() + " dB-Hz 유지 · " + usage);
        }

        add(id, "TEST", "계산 조건", true,
                "Ephemeris·Doppler·C/N0 등 원본 유지 · 기존 RTKLIB GPS L1 C/A SPP 재사용 · 원본 JSON/RAW 변경 없음");
        add(id, "TEST", "결과 해석", true,
                "공통 의사거리 증가분은 수신기 시계 오차로 추정될 수 있습니다. "
                        + "원본 Doppler를 사용하므로 속도 변화가 작을 수 있으나 동일함을 보장하지 않습니다. "
                        + "실제 미래 GNSS 관측 재현은 아닙니다.");
        if (evidence.error() != null) {
            add(id, "TEST", "WARN", "지연 계산", false, evidence.error());
        }
    }

    private static String gpsTime(DtnDelay.Time time)
    {
        if (time == null) {
            return "계산 불가";
        }
        return String.format(Locale.ROOT, "Week %d / TOW %.9f s", time.week(), time.towSeconds());
    }

    @Scheduled(fixedDelay = 600000) @Transactional
    public void cleanup() {
        repository.deleteByScopeTypeNotAndOccurredAtBefore("TEST",Instant.now().minus(Duration.ofDays(7)));
        captureStages.keySet().removeIf(id->!exists(id)); captureTimes.keySet().retainAll(captureStages.keySet());
    }
}
