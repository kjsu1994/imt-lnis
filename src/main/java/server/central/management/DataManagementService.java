package server.central.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import server.central.config.StorageProperties;
import server.central.dtn.*;
import server.central.input.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** 로컬 자료만 관리한다. 조회는 메타데이터만, 삭제는 미리보기와 실행 직전 검사를 거친다. */
@Service @RequiredArgsConstructor @Slf4j
public class DataManagementService {
    private final EntityManager em;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    private final DataManagementGuard guard;
    private final StorageProperties storage;
    private final DtnService dtn;
    private final IqService iq;
    private final InputBufferService inputs;
    private final InputBufferRepository inputRepository;
    @Value("${lnis.node.role:server}") private String role;
    @Value("${lnis.iq.directory:/exchange}") private String iqDirectory;
    @Value("${spring.datasource.url:}") private String databaseUrl;
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","CANCELLED","INCONCLUSIVE");
    // AFS는 과거 관리 작업 이력의 역직렬화에만 사용한다.
    public enum Kind { DTN, AFS, RECEIPT, INPUT, IQ }
    private static final List<Kind> ACTIVE_KINDS=List.of(Kind.DTN,Kind.RECEIPT,Kind.INPUT,Kind.IQ);
    private static void requireSupported(Kind kind) {
        if(kind==Kind.AFS) throw new ResponseStatusException(HttpStatus.GONE,"독립 AFS 시험 관리 기능은 종료되었습니다. 기존 기록은 DB에 보관됩니다.");
    }
    public record Key(Kind kind,UUID id) {
        public Key { if(kind==null || id==null) throw new IllegalArgumentException("자료 종류와 ID가 필요합니다."); }
        public String code(){return kind+":"+id;}
    }
    public record Row(Key key,String name,String state,Instant createdAt,Instant updatedAt,long bytes,boolean pinned,String blocked) {}
    public record Page(List<Row> items,long total,int page) {}
    public record Policy(boolean enabled,int days) {
        public Policy { if(days<1 || days>3650) throw new IllegalArgumentException("보관 기간은 1~3650일입니다."); }
    }
    public record Settings(Policy tests,Policy receipts,Policy files) {
        public Settings { if(tests==null || receipts==null || files==null) throw new IllegalArgumentException("보관 설정을 모두 지정하세요."); }
        static Settings defaults(){return new Settings(new Policy(false,30),new Policy(false,30),new Policy(false,30));}
    }
    public record PlanItem(Row row,List<Key> related,long logs,long receipts,long evidence,String blocked) {}
    public record Preview(UUID token,String role,Instant expiresAt,List<PlanItem> items) {}
    public record Result(Key key,String status,String message) {}
    public record Operation(UUID id,Instant at,String source,List<Result> results) {}
    public record PolicyPreview(Preview preview,Settings settings) {}

    private <T> T transaction(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status->action.get());
    }
    private void put(String id,String kind,Object value) {
        try {
            ManagementEntry entry=em.find(ManagementEntry.class,id);
            if(entry==null) entry=new ManagementEntry(id,kind,json.writeValueAsString(value));
            else entry.setValue(json.writeValueAsString(value));
            em.merge(entry);
        } catch(java.io.IOException error) {throw new IllegalStateException(error);}
    }
    private <T> T stored(String id,Class<T> type) {
        var entry=em.find(ManagementEntry.class,id);
        if(entry==null) return null;
        try{return json.readValue(entry.getValue(),type);}catch(Exception error){throw new IllegalStateException("관리 기록 조회 실패",error);}
    }
    public Settings settings() {
        var result=stored("SETTINGS",Settings.class);return result==null?Settings.defaults():result;
    }
    private long count(String query,UUID id) {
        return em.createQuery(query,Long.class).setParameter("id",id).getSingleResult();
    }
    private boolean pinned(Key key) {return em.find(ManagementEntry.class,"PIN:"+key.code())!=null;}
    private String table(Kind kind) {
        return switch(kind){case DTN->"DtnJob";case RECEIPT->"DtnReceipt";case INPUT->"InputBufferEntity";default->throw new IllegalArgumentException();};
    }
    private String idField(Kind kind){return kind==Kind.INPUT?"inputId":"id";}
    private String dateField(Kind kind){return kind==Kind.RECEIPT?"arrivedAt":"createdAt";}
    private String projection(Kind kind) {
        return switch(kind){
            case DTN->"e.id,e.testType,e.state,e.createdAt,e.updatedAt,e.cancelPending";
            case RECEIPT->"e.id,e.contentType,e.status,e.arrivedAt,e.arrivedAt,e.sizeBytes";
            case INPUT->"e.inputId,e.fileName,e.complete,e.createdAt,e.completedAt,e.receivedSize";
            default->throw new IllegalArgumentException();
        };
    }
    private Row row(Kind kind,Object[] values) {
        Key key=new Key(kind,(UUID)values[0]);
        String state=kind==Kind.INPUT?(Boolean.TRUE.equals(values[2])?"COMPLETE":"INCOMPLETE"):String.valueOf(values[2]);
        String blocked="";
        if((kind==Kind.DTN) && !TERMINAL.contains(state)) blocked="진행 중인 시험";
        if(kind==Kind.DTN && Boolean.TRUE.equals(values[5])) blocked="상대 노드 중지 확인 대기";
        if(kind==Kind.INPUT && state.equals("INCOMPLETE") && ((Instant)values[3]).isAfter(Instant.now().minus(Duration.ofHours(1)))) blocked="최근 업로드·수집 중인 입력";
        if(kind==Kind.INPUT && referenced(key,null)) blocked="시험에서 참조하는 입력";
        if(kind==Kind.RECEIPT && referenced(key,null)) blocked="시험과 연결된 원문: 시험 기록에서 함께 삭제하세요.";
        boolean pin=pinned(key);if(pin) blocked="보관 고정";
        return new Row(key,Objects.toString(values[1],kind.name()),state,(Instant)values[3],(Instant)values[4],
                kind==Kind.INPUT || kind==Kind.RECEIPT?((Number)values[5]).longValue():0,pin,blocked);
    }
    public Page list(Kind kind,int page,String search,String state,Instant from,Instant to) {
        requireSupported(kind);
        if(page<0 || page>1_000_000) throw new IllegalArgumentException("페이지 범위 오류");
        if(from!=null && to!=null && from.isAfter(to)) throw new IllegalArgumentException("조회 기간 오류");
        search=search==null?"":search.trim();state=state==null?"":state.trim();
        if(search.length()>100 || state.length()>40) throw new IllegalArgumentException("검색어가 너무 깁니다.");
        if(kind==Kind.IQ) {
            final String q=search,filter=state;
            var rows=iqRows().stream().filter(r->r.key().id().toString().contains(q))
                .filter(r->filter.isEmpty() || r.state().equals(filter))
                .filter(r->from==null || !r.createdAt().isBefore(from)).filter(r->to==null || r.createdAt().isBefore(to)).toList();
            return new Page(rows.stream().skip((long)page*50).limit(50).toList(),rows.size(),page);
        }
        String where=" where 1=1";
        var parameters=new HashMap<String,Object>();
        if(!search.isEmpty()){where+=" and cast(e."+idField(kind)+" as string) like :search";parameters.put("search","%"+search.replace("%","").replace("_","")+"%");}
        if(!state.isEmpty()) {
            if(kind==Kind.INPUT){where+=" and e.complete=:complete";parameters.put("complete",state.equals("COMPLETE"));}
            else {where+=" and cast(e."+(kind==Kind.RECEIPT?"status":"state")+" as string)=:state";parameters.put("state",state);}
        }
        if(from!=null){where+=" and e."+dateField(kind)+">=:from";parameters.put("from",from);}
        if(to!=null){where+=" and e."+dateField(kind)+"<:to";parameters.put("to",to);}
        var query=em.createQuery("select "+projection(kind)+" from "+table(kind)+" e"+where+" order by e."+dateField(kind)+" desc,e."+idField(kind),Object[].class);
        var total=em.createQuery("select count(e) from "+table(kind)+" e"+where,Long.class);
        parameters.forEach((k,v)->{query.setParameter(k,v);total.setParameter(k,v);});
        return new Page(query.setFirstResult(page*50).setMaxResults(50).getResultList().stream().map(v->row(kind,v)).toList(),total.getSingleResult(),page);
    }
    private Row get(Key key) {
        requireSupported(key.kind());
        if(key.kind()==Kind.IQ) return iqRows().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"파일이 없습니다."));
        var values=em.createQuery("select "+projection(key.kind())+" from "+table(key.kind())+" e where e."+idField(key.kind())+"=:id",Object[].class).setParameter("id",key.id()).getResultList();
        if(values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"자료가 없습니다.");
        return row(key.kind(),values.getFirst());
    }
    private boolean referenced(Key resource,Key excluding) {
        if(resource.kind()==Kind.RECEIPT) {
            var ids=em.createQuery("select e.testId from DtnReceipt e where e.id=:id",UUID.class).setParameter("id",resource.id()).getResultList();
            return !ids.isEmpty() && ids.getFirst()!=null && count("select count(e) from DtnJob e where e.id=:id",ids.getFirst())>0;
        }
        String field=resource.kind()==Kind.INPUT?"inputId":"iqFileId";
        var query=em.createQuery("select e.id from DtnJob e where e."+field+"=:id",UUID.class).setParameter("id",resource.id());
        if(query.getResultList().stream().anyMatch(id->excluding==null || excluding.kind()!=Kind.DTN || !excluding.id().equals(id))) return true;
        return false;
    }
    private List<Row> iqRows() {
        Path root=Path.of(iqDirectory).toAbsolutePath().normalize();
        var ids=new LinkedHashSet<UUID>();var rows=new ArrayList<Row>();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) return rows;
        try(var paths=Files.list(root)) {
            paths.forEach(path->{String name=path.getFileName().toString();
                var match=java.util.regex.Pattern.compile("(?:work-)?([0-9a-fA-F-]{36})(?:\\.bin(?:\\.part)?|\\.json|\\.context\\.json)?").matcher(name);
                if(match.matches()) try{ids.add(UUID.fromString(match.group(1)));}catch(IllegalArgumentException ignored){}
            });
            for(UUID id:ids) {
                Key key=new Key(Kind.IQ,id);var files=iqPaths(id);long bytes=0;Instant created=null;
                for(Path file:files) if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                    Instant at=Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant();if(created==null || at.isBefore(created)) created=at;
                    bytes+=size(file);
                }
                String state=iq.managementBusy(id)?"GENERATING":Files.isRegularFile(root.resolve(id+".bin"),LinkOption.NOFOLLOW_LINKS)?"READY":"FAILED";
                String blocked=state.equals("GENERATING")?"생성 중인 파일":referenced(key,null)?"시험에서 참조하는 파일":"";
                boolean pin=pinned(key);if(pin)blocked="보관 고정";
                rows.add(new Row(key,id+".bin",state,created==null?Instant.now():created,created,bytes,pin,blocked));
            }
        } catch(java.io.IOException error){throw new IllegalStateException("I/Q 파일 목록 조회 실패",error);}
        rows.sort(Comparator.comparing(Row::createdAt).reversed());return rows;
    }
    private List<Path> iqPaths(UUID id) {
        Path root=Path.of(iqDirectory).toAbsolutePath().normalize();
        return List.of(root.resolve(id+".bin"),root.resolve(id+".json"),root.resolve(id+".context.json"),root.resolve(id+".bin.part"),root.resolve("work-"+id));
    }
    private long size(Path path) throws java.io.IOException {
        if(Files.isSymbolicLink(path)) return 0;
        if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return Files.size(path);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))return 0;
        try(var files=Files.walk(path)) {long total=0;for(Path file:files.toList()) if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))total+=Files.size(file);return total;}
    }
    public Map<String,Object> summary() {
        var result=new LinkedHashMap<String,Object>();result.put("role",role);result.put("localOnly",true);result.put("settings",settings());
        var counts=new LinkedHashMap<String,Long>();for(Kind kind:ACTIVE_KINDS)counts.put(kind.name(),list(kind,0,"","",null,null).total());result.put("counts",counts);
        try {
            result.put("inputBytes",size(storage.getDataDirectory().resolve("files/inputs")));
            result.put("iqBytes",size(Path.of(iqDirectory)));
            Long dbBytes=null;
            if(databaseUrl.startsWith("jdbc:h2:file:")) {String file=databaseUrl.substring(13).split(";",2)[0];if(!file.startsWith("~")) {Path db=Path.of(file+".mv.db");if(Files.isRegularFile(db))dbBytes=Files.size(db);}}
            result.put("databaseBytes",dbBytes);
        }catch(java.io.IOException error){result.put("sizeMessage","일부 파일 용량을 확인할 수 없습니다.");}
        return result;
    }
    public Map<String,Object> detail(Key key) {
        Row row=get(key);var result=new LinkedHashMap<String,Object>();result.put("row",row);
        String base="/lnis/api/v1/";
        result.put("detailUrl",switch(key.kind()) {
            case DTN->base+"dtn/tests/"+key.id()+"/report";
            case RECEIPT->base+"dtn/receipts/"+key.id()+"/body";
            default->base+"data-management/files/"+key.kind()+"/"+key.id();
        });
        if(key.kind()==Kind.DTN){var job=em.find(DtnJob.class,key.id());result.put("message",job.getMessage());result.put("sentAvailable",job.getSentJson()!=null);result.put("receivedAvailable",job.getReceivedRawJson()!=null || job.getReceivedJson()!=null);}
        result.put("related",plan(key));return result;
    }
    private PlanItem plan(Key key) {
        requireSupported(key.kind());
        if(guard.deleted(key.kind().name(),key.id()))return new PlanItem(new Row(key,"삭제된 자료","DELETED",null,null,0,false,""),List.of(),0,0,0,"");
        Row row=get(key);var related=new ArrayList<Key>();long receipts=0;
        if(key.kind()==Kind.DTN) {
            UUID inputId,iqId=null;
            {var refs=em.createQuery("select e.inputId,e.iqFileId from DtnJob e where e.id=:id",Object[].class).setParameter("id",key.id()).getSingleResult();inputId=(UUID)refs[0];iqId=(UUID)refs[1];receipts=count("select count(e) from DtnReceipt e where e.testId=:id",key.id());}
            if(inputId!=null && count("select count(e) from InputBufferEntity e where e.inputId=:id",inputId)>0) addRelated(related,new Key(Kind.INPUT,inputId),key);
            if(iqId!=null && iqExists(iqId)) addRelated(related,new Key(Kind.IQ,iqId),key);
        }
        String blocked=row.blocked();
        if(key.kind()==Kind.DTN) {
            var receiptIds=em.createQuery("select e.id from DtnReceipt e where e.testId=:id",UUID.class).setParameter("id",key.id()).getResultList();
            if(receiptIds.stream().anyMatch(id->pinned(new Key(Kind.RECEIPT,id)))) blocked="연결된 수신 원문이 보관 고정 상태입니다.";
        }
        long logs=count("select count(e) from DtnLogEntry e where e.scopeId=:id",key.id());
        for(Key resource:related)logs+=count("select count(e) from DtnLogEntry e where e.scopeId=:id",resource.id());
        return new PlanItem(row,related,logs,receipts,0,blocked);
    }
    private boolean iqExists(UUID id){return iqRows().stream().anyMatch(r->r.key().id().equals(id));}
    private void addRelated(List<Key> related,Key resource,Key owner) {
        if(!pinned(resource) && !referenced(resource,owner)) related.add(resource);
    }
    public Preview preview(List<Key> keys) {
        if(keys==null || keys.isEmpty() || keys.size()>500 || keys.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("1~500건을 선택하세요.");
        var result=new Preview(UUID.randomUUID(),role,Instant.now().plus(Duration.ofMinutes(10)),keys.stream().distinct().map(this::plan).toList());
        transaction(()->{put("PREVIEW:"+result.token(),"PREVIEW",result);return null;});return result;
    }
    public void pin(Key key,boolean pin) {
        if(key==null)throw new IllegalArgumentException("??? ?????.");
        var lock=guard.gate.writeLock();lock.lock();
        try {get(key);transaction(()->{String id="PIN:"+key.code();if(pin)put(id,"PIN",key);else {var entry=em.find(ManagementEntry.class,id);if(entry!=null)em.remove(entry);}return null;});}
        finally{lock.unlock();}
    }
    private void idle() {
        if(dtn.managementBusy() || iq.managementBusy(null)
            || em.createQuery("select count(e) from DtnJob e where e.state not in ('COMPLETED','FAILED','CANCELLED','INCONCLUSIVE') or e.cancelPending=true",Long.class).getSingleResult()>0
            || em.createQuery("select count(e) from AgentEntity e where cast(e.state as string)='BUSY'",Long.class).getSingleResult()>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"시험·수집·파일 작업 또는 중지 확인이 진행 중입니다. 완료 후 정리하세요.");
    }
    private <T> T exclusive(Supplier<T> action) {
        var lock=guard.gate.writeLock();lock.lock();
        try {synchronized(dtn){synchronized(iq){synchronized(inputs){idle();return action.get();}}}}
        finally{lock.unlock();}
    }
    public Operation execute(UUID token,String source) {
        return exclusive(()->{
            var preview=stored("PREVIEW:"+token,Preview.class);
            if(preview==null || preview.expiresAt().isBefore(Instant.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"미리보기가 만료됐습니다. 다시 확인하세요.");
            for(var item:preview.items()) {
                requireSupported(item.row().key().kind());
                item.related().forEach(key->requireSupported(key.kind()));
            }
            var results=new ArrayList<Result>();
            UUID operationId=UUID.randomUUID();Instant operationAt=Instant.now();
            for(var item:preview.items()) results.add(new Result(item.row().key(),"FAILED","정리 미완료: 재시도할 수 있습니다."));
            transaction(()->{put("OP:"+operationId,"OP",new Operation(operationId,operationAt,source,results));return null;});
            int index=0;
            for(var item:preview.items()) {
                Key key=item.row().key();
                try {
                    transaction(()->{
                        if(guard.deleted(key.kind().name(),key.id()))return null;
                        var current=plan(key);
                        if(!current.blocked().isEmpty())throw new IllegalStateException(current.blocked());
                        if(!new HashSet<>(item.related()).containsAll(current.related()) || current.receipts()>item.receipts())throw new IllegalStateException("연관 자료가 변경됐습니다. 다시 미리보기 하세요.");
                        for(Key related:current.related()) remove(related);
                        remove(key);return null;
                    });
                    results.set(index,new Result(key,"DELETED","삭제 완료"));
                }catch(Exception error){results.set(index,new Result(key,"FAILED",Objects.toString(error.getMessage(),"삭제 실패")));log.warn("DATA_DELETE_FAILED kind={} id={} reason={}",key.kind(),key.id(),error.getClass().getSimpleName());}
                transaction(()->{put("OP:"+operationId,"OP",new Operation(operationId,operationAt,source,results));return null;});
                index++;
            }
            var operation=new Operation(operationId,operationAt,source,results);
            transaction(()->{put("OP:"+operation.id(),"OP",operation);var entry=em.find(ManagementEntry.class,"PREVIEW:"+token);if(entry!=null)em.remove(entry);return null;});
            log.info("DATA_CLEANUP id={} source={} deleted={} failed={}",operation.id(),source,results.stream().filter(r->r.status().equals("DELETED")).count(),results.stream().filter(r->r.status().equals("FAILED")).count());
            return operation;
        });
    }
    private void remove(Key key) {
        requireSupported(key.kind());
        if(pinned(key))throw new IllegalStateException("보관 고정 자료입니다.");
        if(key.kind()==Kind.INPUT) {
            var input=em.find(InputBufferEntity.class,key.id());if(input!=null)inputRepository.delete(key.id(),input.chunkCount());
        } else if(key.kind()==Kind.IQ) {
            try {for(Path path:iqPaths(key.id())) deletePath(path);iq.delete(key.id());}
            catch(java.io.IOException error){throw new IllegalStateException("파일 삭제 실패: 재시도하세요.",error);}
        } else if(key.kind()==Kind.DTN) {
            delete("DtnReceipt","testId",key.id());delete("DtnJob","id",key.id());
        } else delete("DtnReceipt","id",key.id());
        delete("DtnLogEntry","scopeId",key.id());
        put("DELETED:"+key.code(),"DELETED",key);
    }
    private void delete(String entity,String field,UUID id) {em.createQuery("delete from "+entity+" e where e."+field+"=:id").setParameter("id",id).executeUpdate();}
    private void deletePath(Path path) throws java.io.IOException {
        Path root=Path.of(iqDirectory).toAbsolutePath().normalize();path=path.toAbsolutePath().normalize();
        if(!path.startsWith(root) || path.equals(root) || Files.isSymbolicLink(root))throw new java.io.IOException("허용되지 않은 파일 경로");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        try(var walk=Files.walk(path)) {
            var paths=walk.sorted(Comparator.reverseOrder()).toList();
            for(Path item:paths)if(Files.isSymbolicLink(item) || !item.toRealPath().startsWith(root.toRealPath()))throw new java.io.IOException("심볼릭 링크·외부 경로는 정리할 수 없습니다.");
            for(Path item:paths)Files.deleteIfExists(item);
        }
    }
    public List<Operation> history() {
        return em.createQuery("select e from ManagementEntry e where e.kind='OP' order by e.createdAt desc",ManagementEntry.class).setMaxResults(50).getResultList().stream().map(e->stored(e.getId(),Operation.class)).toList();
    }
    public Preview retry(UUID id) {
        var op=stored("OP:"+id,Operation.class);if(op==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return preview(op.results().stream().filter(r->r.status().equals("FAILED")).map(Result::key).toList());
    }
    private List<Key> candidates(Settings settings) {
        var keys=new ArrayList<Key>();Instant now=Instant.now();
        for(Kind kind:ACTIVE_KINDS) {
            Policy policy=kind==Kind.DTN?settings.tests():kind==Kind.RECEIPT?settings.receipts():settings.files();
            if(!policy.enabled())continue;
            Instant cutoff=now.minus(Duration.ofDays(policy.days()));
            for(int page=0;keys.size()<500;page++) {
                Page rows=list(kind,page,"","",null,null);
                for(Row row:rows.items()) {
                    Instant date=(kind==Kind.DTN)?row.updatedAt():row.createdAt();
                    if(row.blocked().isEmpty() && date!=null && date.isBefore(cutoff)) keys.add(row.key());
                    if(keys.size()==500)break;
                }
                if((long)(page+1)*50>=rows.total())break;
            }
        }
        return keys;
    }
    public PolicyPreview previewPolicy(Settings proposed) {
        var keys=candidates(proposed);
        var preview=keys.isEmpty()?new Preview(UUID.randomUUID(),role,Instant.now().plusSeconds(600),List.of()):preview(keys);
        var result=new PolicyPreview(preview,proposed);
        transaction(()->{put("POLICY:"+preview.token(),"POLICY",result);return null;});return result;
    }
    public Settings savePolicy(UUID token) {
        var lock=guard.gate.writeLock();lock.lock();
        try{return transaction(()->{
            var proposed=stored("POLICY:"+token,PolicyPreview.class);
            if(proposed==null || proposed.preview().expiresAt().isBefore(Instant.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"보관 설정 미리보기가 만료됐습니다.");
            put("SETTINGS","SETTINGS",proposed.settings());em.remove(em.find(ManagementEntry.class,"POLICY:"+token));
            log.info("DATA_RETENTION_SETTINGS_UPDATED tests={} receipts={} files={}",proposed.settings().tests(),proposed.settings().receipts(),proposed.settings().files());
            return proposed.settings();
        });}finally{lock.unlock();}
    }
    public Preview previewCleanup(){return previewPolicy(settings()).preview();}
    @Scheduled(fixedDelayString="${lnis.storage.cleanup-delay:PT10M}",initialDelayString="${lnis.storage.cleanup-delay:PT10M}")
    public void cleanup() {
        try {
            var keys=candidates(settings());if(!keys.isEmpty())execute(preview(keys).token(),"AUTOMATIC");
            transaction(()->{em.createQuery("delete from ManagementEntry e where e.kind in ('PREVIEW','POLICY') and e.createdAt<:cutoff").setParameter("cutoff",Instant.now().minus(Duration.ofHours(1))).executeUpdate();return null;});
        }catch(Exception error){log.warn("DATA_CLEANUP_DEFERRED reason={}",error.getMessage());}
    }
    public void download(Key key,java.io.OutputStream output) throws java.io.IOException {
        if(key.kind()!=Kind.INPUT && key.kind()!=Kind.IQ)throw new IllegalArgumentException("파일 자료를 선택하세요.");
        var lock=guard.gate.readLock();lock.lock();
        try {
            get(key);
            Path root=key.kind()==Kind.IQ?Path.of(iqDirectory):storage.getDataDirectory().resolve("files/inputs");root=root.toAbsolutePath().normalize();
            Path file=root.resolve(key.id()+(key.kind()==Kind.IQ?".bin":".graw"));
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file) || !file.toRealPath().getParent().equals(root.toRealPath()))throw new java.io.IOException("완료된 파일이 없습니다.");
            Files.copy(file,output);
        } finally{lock.unlock();}
    }
}
