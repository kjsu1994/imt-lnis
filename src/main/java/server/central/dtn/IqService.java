package server.central.dtn;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import server.shared.codec.Hashing;
import server.shared.model.DtnModels.IqFile;
import java.nio.file.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One on-demand native process. Shared files stay outside the DB and HTTP body. */
@Service
public class IqService {
  public static final long EXPECTED_BYTES = 90L * 12_000_000 * 2;
  private final ObjectMapper json;
  private final Path root, simulator;
  private final boolean enabled;
  private volatile Process process;
  private UUID active;
  @org.springframework.beans.factory.annotation.Autowired(required=false) private DtnLogService logs;
  private void trace(UUID id,String stage,boolean detail,String message) { if(logs!=null) logs.add(id,"IQ",stage,detail,message); }
  private final Map<UUID, Map<String,Object>> jobs = new LinkedHashMap<>();

  public IqService(ObjectMapper json, @Value("${lnis.iq.directory:/exchange}") String root,
      @Value("${lnis.iq.simulator:/app/iq/afs_sim}") String simulator,
      @Value("${lnis.iq.enabled:false}") boolean enabled) {
    this.json = json; this.root = Path.of(root).toAbsolutePath().normalize();
    this.simulator = Path.of(simulator).toAbsolutePath().normalize(); this.enabled = enabled;
  }
  public boolean enabled() { return enabled && Files.isExecutable(simulator); }
  public synchronized List<Map<String,Object>> recent() throws IOException {
    if (Files.isDirectory(root)) {
      try (var files = Files.list(root)) {
        for (Path file : files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).limit(50).toList()) {
          UUID id = UUID.fromString(file.getFileName().toString().substring(0,36));
          if (!jobs.containsKey(id)) {
            try { status(id); } catch (IOException | IllegalArgumentException ignored) { }
          }
        }
      }
    }
    return jobs.keySet().stream().toList().reversed().stream().map(id -> {
      try { return status(id); } catch (IOException error) { return Map.<String,Object>of("id",id,"state","FAILED","message","파일 조회 실패"); }
    }).toList();
  }
  public synchronized void delete(UUID id) throws IOException {
    if (id.equals(active)) throw new IllegalStateException("생성 중인 파일은 취소 후 삭제하세요.");
    Path file = root.resolve(id + ".bin");
    if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("심볼릭 링크는 삭제할 수 없습니다.");
    Files.deleteIfExists(file);
    Files.deleteIfExists(root.resolve(id + ".json"));
    jobs.remove(id);
  }
  public synchronized Map<String,Object> start() throws IOException {
    if (!enabled()) throw new IllegalStateException("I/Q 생성기를 설정하세요.");
    throw new IllegalArgumentException("GNSS 입력을 선택하세요.");
  }
  public static String earthInput(List<byte[]> records, server.shared.model.DtnModels.Pvt pvt) {
    if(pvt==null || !pvt.isPositionValid() || !pvt.isVelocityValid()) throw new IllegalArgumentException("유효 PVT 필요");
    var text=new StringBuilder("LNIS-IQ-EARTH-1 ").append(pvt.getWeek()).append(' ').append(pvt.getTowSeconds());
    for(double value:pvt.getEcefMeters()) text.append(' ').append(value);
    for(double value:pvt.getVelocityMetersPerSecond()) text.append(' ').append(value);
    text.append(' ').append(pvt.getReceiverClockBiasSeconds()).append('\n');
    boolean found=false;
    for(byte[] record:records) {
      var message=server.shared.codec.GrawCodec.decode(record).message();
      if(message instanceof server.shared.codec.GrawCodec.NavigationUpdate nav && nav.constellationId()==0
          && nav.signalId()==0 && nav.words().size()==10 && ((nav.words().getFirst()>>>22)&255)==0x8b) {
        text.append("N ").append(nav.satelliteId());
        for(long word:nav.words()) text.append(' ').append((word>>>6)&0xffffff);
        text.append('\n');
      } else if(message instanceof server.shared.codec.GrawCodec.ObservationEpoch epoch
          && epoch.week()==pvt.getWeek() && Double.compare(epoch.receiverTowSeconds(),pvt.getTowSeconds())==0) {
        epoch.observations().stream().filter(o->o.constellationId()==0 && o.signalId()==0 && (o.trackingStatus()&1)!=0)
          .map(o->o.satelliteId()).distinct().forEach(prn->text.append("P ").append(prn).append('\n'));
        found=true; break;
      }
    }
    if(!found) throw new IllegalArgumentException("PVT 관측 시각 불일치");
    return text.toString();
  }
  public synchronized Map<String,Object> start(String earthInput) throws IOException { return start(earthInput,null); }
  public synchronized Map<String,Object> start(String earthInput,UUID inputId) throws IOException {
    if(earthInput==null || !earthInput.startsWith("LNIS-IQ-EARTH-1 ")) throw new IllegalArgumentException("GNSS 입력 필요");
    if (!enabled()) throw new IllegalStateException("I/Q 생성기를 설정하세요.");
    if (active != null) throw new IllegalStateException("I/Q 생성 작업 진행 중");
    Files.createDirectories(root);
    if (Files.getFileStore(root).getUsableSpace() < EXPECTED_BYTES + 268_435_456L)
      throw new IllegalStateException("I/Q 생성 공간 부족: 최소 2.43 GB가 필요합니다.");
    UUID id = UUID.randomUUID();
    if(logs!=null) logs.copy(inputId,id,"IQ");
    trace(id,"I/Q 입력",true,"대상 PRN "+earthInput.lines().filter(s->s.startsWith("P ")).toList()+" · 항법 레코드 "+earthInput.lines().filter(s->s.startsWith("N ")).count()+"건");
    active = id;
    set(id, "GENERATING", "GNSS 기반 90초 AFS I/Q 생성 중 · PRN별 SB2 반복", null);
    Thread.ofVirtual().name("iq-generate").start(() -> generate(id,earthInput));
    return status(id);
  }
  private void generate(UUID id, String earthInput) {
    Path work = root.resolve("work-" + id), part = root.resolve(id + ".bin.part");
    try {
      Files.createDirectory(work);
      Files.writeString(work.resolve("earth-input.txt"),earthInput);
      for (String asset : List.of("008_Weil1500hex210prns.txt"))
        Files.copy(simulator.getParent().resolve(asset), work.resolve(asset));
      synchronized (this) {
        if (!id.equals(active) || cancelled(id)) return;
        process = new ProcessBuilder(simulator.toString(), "-e", work.resolve("earth-input.txt").toString(), "-t", "90", "-s", "12000000", "-b", "2", part.toString())
            .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("generation.log").toFile()).start();
      }
      trace(id,"I/Q 생성",true,"기존 AFS 생성기 실행 · 90초 · 12 MHz · I/Q signed int8");
      Process running = process;
      long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30), started=System.nanoTime();
      int previous=0;
      while(!running.waitFor(2,TimeUnit.SECONDS)) {
        if(System.nanoTime()>deadline) { running.destroyForcibly(); throw new IOException("I/Q 생성 제한 시간 초과"); }
        long written=Files.exists(part)?Files.size(part):0;
        int bucket=(int)Math.min(9,written*10/EXPECTED_BYTES);
        if(bucket>previous) { previous=bucket; trace(id,"I/Q 생성",true,"파일 출력 "+(bucket*10)+"% · "+written+" / "+EXPECTED_BYTES+" bytes"); }
      }
      synchronized (this) { if (!id.equals(active) || cancelled(id)) return; }
      if (running.exitValue() != 0 || Files.size(part) != EXPECTED_BYTES)
        throw new IOException("I/Q 생성 실패: exit=" + running.exitValue() + ", bytes=" + Files.size(part) + ", expected=" + EXPECTED_BYTES);
      trace(id,"I/Q 검증",true,"파일 출력 100% · 크기 확인 완료 · "+Files.size(part)+" bytes · 생성 소요 "+((System.nanoTime()-started)/1_000_000)+" ms");
      trace(id,"I/Q 검증",true,"SHA-256 계산 시작 · 아직 전송 준비 완료가 아닙니다.");
      String digest = sha256(part);
      trace(id,"I/Q 검증",true,"SHA-256 계산 완료 · "+digest);
      synchronized (this) {
        if (!id.equals(active) || cancelled(id)) return;
        Files.move(part, root.resolve(id + ".bin"), StandardCopyOption.ATOMIC_MOVE);
        IqFile file = new IqFile("/exchange/" + id + ".bin", EXPECTED_BYTES, digest, 90, 12_000_000, "IQ_INTERLEAVED_INT8", 2);
        json.writeValue(root.resolve(id + ".json").toFile(), file);
        set(id, "READY", "파일 생성·크기·SHA-256 검증 완료", file);
      }
    } catch (Exception error) {
      synchronized (this) { if (id.equals(active) && !cancelled(id)) set(id, "FAILED", error.getMessage(), null); }
    } finally {
      synchronized (this) {
        if (id.equals(active)) { active = null; process = null; }
      }
      try { Files.deleteIfExists(part); } catch (IOException ignored) { }
    }
  }
  public synchronized Map<String,Object> status(UUID id) throws IOException {
    Map<String,Object> job = jobs.get(id);
    if (job == null && Files.isRegularFile(root.resolve(id + ".json"))) {
      IqFile file = json.readValue(root.resolve(id + ".json").toFile(), IqFile.class);
      set(id, "READY", "저장된 I/Q 파일", file); job = jobs.get(id);
    }
    if (job == null) throw new IllegalArgumentException("I/Q 작업을 찾을 수 없습니다.");
    var result = new LinkedHashMap<>(job);
    Path part = root.resolve(id + ".bin.part");
    // 취소/완료 처리 중 .part가 삭제·이동될 수 있다. exists→size 사이 경합도 정상 상태다.
    long generated = "READY".equals(job.get("state")) ? EXPECTED_BYTES : 0;
    try { generated = Files.size(part); }
    catch (NoSuchFileException ignored) { /* 아직 생성 전이거나 이미 취소/완료됨 */ }
    result.put("generatedBytes", generated);
    result.put("expectedBytes", EXPECTED_BYTES);
    if (job.get("file") instanceof IqFile file && "READY".equals(job.get("state"))) {
      try { result.put("preview", preview(file)); }
      catch (IOException | IllegalArgumentException error) { result.put("state", "FAILED"); result.put("message", "저장된 파일이 없거나 읽을 수 없습니다."); }
    }
    return result;
  }
  public synchronized void cancel(UUID id) {
    if (!id.equals(active)) return;
    // Keep the slot until the worker has reaped the native process.
    if (process != null) process.destroyForcibly();
    set(id, "CANCELLED", "생성 취소", null);
  }
  private boolean cancelled(UUID id) { return jobs.containsKey(id) && "CANCELLED".equals(jobs.get(id).get("state")); }
  private void set(UUID id, String state, String message, IqFile file) {
    var value = new LinkedHashMap<String,Object>();
    value.put("id", id); value.put("state", state); value.put("message", message);
    value.put("updatedAt", Instant.now().toString()); if (file != null) value.put("file", file);
    jobs.put(id, value);
    if(logs!=null && !("READY".equals(state) && "저장된 I/Q 파일".equals(message)))
      logs.add(id,"IQ","FAILED".equals(state)?"ERROR":"CANCELLED".equals(state)?"WARN":"INFO","I/Q",false,message);
    if (jobs.size() > 50) jobs.remove(jobs.keySet().iterator().next());
  }
  public IqFile completed(UUID id) throws IOException {
    var job = status(id);
    if (!"READY".equals(job.get("state"))) throw new IllegalStateException("완료된 I/Q 파일만 전송할 수 있습니다.");
    IqFile file = (IqFile)job.get("file"); verify(file); return file;
  }
  public Path path(IqFile file) throws IOException {
    if (file == null || file.filePath() == null || !file.filePath().matches("/exchange/[0-9a-fA-F-]{36}\\.bin"))
      throw new IllegalArgumentException("허용되지 않은 I/Q 공유 경로");
    String name = file.filePath().substring("/exchange/".length());
    UUID.fromString(name.substring(0, 36));
    Path path = root.resolve(name);
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || !path.toRealPath().getParent().equals(root.toRealPath()))
      throw new IllegalArgumentException("완료된 공유 I/Q 파일이 없습니다.");
    return path;
  }
  public Map<String,Object> verify(IqFile file) throws IOException {
    if (file == null || file.sizeBytes() != EXPECTED_BYTES || file.durationSeconds() != 90
        || file.sampleRateHz() != 12_000_000 || file.quantizationBits() != 2
        || !"IQ_INTERLEAVED_INT8".equals(file.sampleFormat())
        || file.sha256() == null || !file.sha256().matches("[0-9A-F]{64}"))
      throw new IllegalArgumentException("I/Q 파일 메타데이터 오류");
    Path path = path(file);
    if (Files.size(path) != file.sizeBytes() || !sha256(path).equals(file.sha256()))
      throw new IllegalArgumentException("I/Q 크기 또는 SHA-256 불일치");
    return Map.of("verdict", "PASS", "sizeBytes", file.sizeBytes(), "sha256", file.sha256(), "filePath", file.filePath());
  }
  public List<List<Integer>> preview(IqFile file) throws IOException {
    try (var in = Files.newInputStream(path(file))) {
      byte[] bytes = in.readNBytes(64); var values = new ArrayList<List<Integer>>();
      for (int i = 0; i + 1 < bytes.length; i += 2) values.add(List.of((int)bytes[i], (int)bytes[i + 1]));
      return values;
    }
  }
  private static String sha256(Path path) throws IOException {
    var digest = Hashing.sha256Digest();
    try (var in = Files.newInputStream(path)) {
      byte[] buffer = new byte[1024 * 1024]; int count;
      while ((count = in.read(buffer)) >= 0) { if (Thread.currentThread().isInterrupted()) throw new IOException("검증 중단"); digest.update(buffer, 0, count); }
    }
    return Hashing.hex(digest.digest());
  }
  @PreDestroy public synchronized void close() { if (process != null) process.destroyForcibly(); }
}
