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

    /** 콘솔 표시만 정렬한다. 잘못된 JSON은 원문을 출력하고 수신 처리를 방해하지 않는다. */
    static String prettyBody(com.fasterxml.jackson.databind.ObjectMapper mapper, String body) {
        try {
            var tree=mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            return tree==null?body:mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch(java.io.IOException error) { return body; }
    }

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
        repository.saveAndFlush(new DtnLogEntry(id,type,Instant.now(),level,stage,detail,clean(message)));
    }

    /** 어댑터 부가 로그만 관리 채널로 공유한다. LNIS 자체 처리 로그는 각 PC에 남긴다. */
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
            repository.save(new DtnLogEntry(id,"TEST",e.occurredAt(),level,"DTN",true,clean(e.message())));
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
