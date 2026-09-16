package server.central.dtn;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import server.shared.codec.NativePvtCodec;
import server.shared.model.DtnModels.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** File-only PocketSDR tracking. Reference positions NEVER enter the native receiver/solver. */
@Service
public class IqReceiver {
  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(IqReceiver.class);
  public static final String METHOD = "AFS_IQ_GPS_LNAV_ASSISTED-v1";
  public record Source(String sha256, IqMetadata metadata, Pvt reference) {}
  public record Result(List<Pvt> pvt, Map<String,Object> observations) {}
  private final Path executable, nativeDirectory;
  private final Semaphore slot = new Semaphore(1);
  private volatile Process process;
  private volatile boolean closed;

  public IqReceiver(@Value("${lnis.iq.receiver:/app/iq/pocket_trk}") String executable,
      @Value("${lnis.native.dir:native}") String nativeDirectory) {
    this.executable = Path.of(executable).toAbsolutePath();
    this.nativeDirectory = Path.of(nativeDirectory).toAbsolutePath();
  }

  public static Source source(String input, String sha256) {
    var lines = input.lines().toList();
    if (lines.isEmpty()) throw new IllegalArgumentException("I/Q 생성 입력 누락");
    String[] header = lines.getFirst().trim().split("\\s+");
    if (header.length != 10 || !header[0].equals("LNIS-IQ-EARTH-1"))
      throw new IllegalArgumentException("I/Q 생성 입력 형식 오류");
    Pvt ref = new Pvt(); ref.setWeek(Integer.parseInt(header[1])); ref.setTowSeconds(Double.parseDouble(header[2]));
    ref.setEcefMeters(new double[]{Double.parseDouble(header[3]),Double.parseDouble(header[4]),Double.parseDouble(header[5])});
    ref.setVelocityMetersPerSecond(new double[]{Double.parseDouble(header[6]),Double.parseDouble(header[7]),Double.parseDouble(header[8])});
    ref.setReceiverClockBiasSeconds(Double.parseDouble(header[9])); ref.setPositionValid(true); ref.setVelocityValid(true);
    var prns = new ArrayList<Integer>(); var nav = new ArrayList<IqNavigation>();
    for (String line : lines.subList(1, lines.size())) {
      String[] row = line.trim().split("\\s+");
      if (row.length == 2 && row[0].equals("P")) prns.add(Integer.parseInt(row[1]));
      else if (row.length == 12 && row[0].equals("N"))
        nav.add(new IqNavigation(Integer.parseInt(row[1]), Arrays.stream(row).skip(2).map(Integer::valueOf).toList()));
      else throw new IllegalArgumentException("I/Q 생성 입력 레코드 오류");
    }
    var meta = new IqMetadata("AFSD", METHOD, ref.getWeek(), ref.getTowSeconds(), "ECEF_CONSTANT_VELOCITY",
        List.copyOf(prns), nav.stream().filter(n->prns.contains(n.prn())).toList());
    validate(meta); validateReference(ref, meta);
    ref.setSatellitesUsed(prns.size());
    return new Source(sha256, meta, ref);
  }

  public static void validate(IqMetadata m) {
    if (m == null || !"AFSD".equals(m.signal()) || !METHOD.equals(m.pvtMethod())
        || !"ECEF_CONSTANT_VELOCITY".equals(m.trajectory()) || m.week()<0 || m.week()>8190
        || !Double.isFinite(m.towSeconds()) || m.towSeconds()<0 || m.towSeconds()>=604800
        || m.prns()==null || m.prns().size()<4 || m.prns().size()>32
        || m.prns().stream().anyMatch(p->p==null || p<1 || p>32)
        || new HashSet<>(m.prns()).size()!=m.prns().size()
        || m.gpsLnav()==null || m.gpsLnav().size()>320)
      throw new IllegalArgumentException("I/Q PVT 메타데이터 오류");
    Map<Integer,Set<Integer>> subframes = new HashMap<>();
    for (var n : m.gpsLnav()) {
      if (n==null || !m.prns().contains(n.prn()) || n.words24()==null || n.words24().size()!=10
          || n.words24().stream().anyMatch(w->w==null || w<0 || w>0xffffff)
          || (n.words24().getFirst()>>>16)!=0x8b)
        throw new IllegalArgumentException("I/Q GPS LNAV 워드 오류");
      int id=(n.words24().get(1)>>>2)&7;
      if(id<1 || id>5) throw new IllegalArgumentException("I/Q GPS LNAV 서브프레임 오류");
      subframes.computeIfAbsent(n.prn(), k->new HashSet<>()).add(id);
    }
    if (m.prns().stream().anyMatch(p->!subframes.getOrDefault(p,Set.of()).containsAll(Set.of(1,2,3))))
      throw new IllegalArgumentException("I/Q PVT에 필요한 LNAV 1·2·3 누락");
  }

  private static void validateReference(Pvt p, IqMetadata m) {
    if (p==null || !p.isPositionValid() || !p.isVelocityValid() || p.getWeek()!=m.week()
        || Double.compare(p.getTowSeconds(),m.towSeconds())!=0 || p.getEcefMeters()==null
        || p.getEcefMeters().length!=3 || p.getVelocityMetersPerSecond()==null
        || p.getVelocityMetersPerSecond().length!=3 || p.getReceiverClockBiasSeconds()==null
        || !Double.isFinite(p.getReceiverClockBiasSeconds())
        || Arrays.stream(p.getEcefMeters()).anyMatch(v->!Double.isFinite(v))
        || Arrays.stream(p.getVelocityMetersPerSecond()).anyMatch(v->!Double.isFinite(v)))
      throw new IllegalArgumentException("I/Q 기준 PVT 오류");
  }

  public Result decode(Path file, IqMetadata metadata, Consumer<String> log) throws Exception {
    validate(metadata);
    if (!Files.isExecutable(executable)) throw new IOException("I/Q 수신 실행기를 설치하세요.");
    if (!slot.tryAcquire(30,TimeUnit.SECONDS)) throw new IOException("다른 I/Q 수신 처리 중입니다. 잠시 후 재시험하세요.");
    Path work = null;
    try {
      if (closed) throw new IOException("I/Q 수신 종료 중");
      work = Files.createTempDirectory("lnis-iq-rx-");
      Path tracking = work.resolve("tracking.log");
      String prns = String.join(",", metadata.prns().stream().map(String::valueOf).toList());
      log.accept("PocketSDR AFS 탐색·추적 시작 · PRN "+prns+" · 90초 파일");
      // Only the file and PRN list are passed. No reference position or RAWX is supplied.
      synchronized (this) {
        if (closed) throw new IOException("I/Q 수신 종료 중");
        process = new ProcessBuilder(executable.toString(), "-sig","AFSD","-prn",prns,
            "-f","12","-fmt","INT8X2","-IQ","2","-ti","0","-tscale","20",
            "-log",tracking.toString(),file.toString()).directory(work.toFile())
            .redirectErrorStream(true).redirectOutput(work.resolve("receiver.log").toFile()).start();
      }
      Process running=process;
      long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(15);
      while(!running.waitFor(2,TimeUnit.SECONDS)) {
        if(closed || System.nanoTime()>deadline || (Files.exists(tracking) && Files.size(tracking)>16_777_216))
          throw new IOException("I/Q 추적 제한 초과 또는 중단");
      }
      if(running.exitValue()!=0 || !Files.isRegularFile(tracking)) {
        try(var errors=Files.newInputStream(work.resolve("receiver.log"))) {
          LOGGER.error("I/Q receiver exit {}: {}",running.exitValue(),
              new String(errors.readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8));
        }
        throw new IOException("I/Q 추적 실행 실패: "+running.exitValue());
      }
      log.accept("탐색·추적 종료 · CRC 통과 채널 관측값 집계·지구 PVT 계산 시작");
      var result = calculate(tracking,metadata);
      log.accept("지구 PVT 계산 완료 · 유효 "+result.pvt().stream().filter(Pvt::isPositionValid).count()+" / "+result.pvt().size()+" 시점 · 항법정보 보조 방식");
      return result;
    } finally {
      Process running=process;
      try {
        if(running!=null && running.isAlive()) { running.destroyForcibly(); running.waitFor(10,TimeUnit.SECONDS); }
      } finally { process=null; slot.release(); }
      if(work!=null) { // Only this invocation's known diagnostic files; no recursive deletion.
        try {
          for(String name:List.of("tracking.log","receiver.log",".pocket_navdata.csv")) Files.deleteIfExists(work.resolve(name));
          Files.deleteIfExists(work);
        } catch(IOException error) { LOGGER.warn("I/Q diagnostic cleanup failed: {}",work,error); }
      }
    }
  }

  Result calculate(Path tracking, IqMetadata m) throws IOException {
    var epochs = parse(tracking,m);
    var values = new ArrayList<Pvt>(); var view = new ArrayList<Object>();
    try (var codec = new NativePvtCodec(nativeDirectory)) {
      for (var n:m.gpsLnav()) codec.navigation(n.prn(),m.week(),n.words24().stream().mapToInt(Integer::intValue).toArray());
      for (var entry:epochs.entrySet()) {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("I/Q PVT 시험 중지");
        double absolute=m.towSeconds()+entry.getKey(); int week=m.week()+(int)(absolute/604800);
        double tow=absolute%604800;
        var input = new double[entry.getValue().size()*4]; int index=0;
        var observations = new ArrayList<Object>();
        for(var measurement:entry.getValue().values()) {
          input[index++]=measurement[0]; input[index++]=measurement[1]; input[index++]=measurement[2]; input[index++]=measurement[3];
          observations.add(Map.of("constellationId",0,"satelliteId",(int)measurement[0],"signalId",0,
              "pseudorangeMeters",measurement[1],"dopplerHz",measurement[2],"carrierToNoiseDbHz",measurement[3],
              "carrierPhaseCycles",-measurement[4],"trackingStatus",1,"source","IQ_TRACKING"));
        }
        Pvt p=codec.solve(week,tow,input);
        p.setMessage("AFS I/Q 추적 · GPS LNAV 보조 · "+(p.isPositionValid()?"지구 ECEF":"측위 불가 · "+p.getMessage()));
        values.add(p);
        view.add(Map.of("observation",Map.of("week",week,"receiverTowSeconds",tow,"observations",observations)));
      }
    }
    return new Result(values,Map.of("source","IQ_TRACKING","epochs",view,"navigationCount",m.gpsLnav().size(),
        "navigation",java.util.stream.IntStream.range(0,m.gpsLnav().size()).mapToObj(i->Map.of(
            "sequence",i+1,"message",Map.of("constellationId",0,"satelliteId",m.gpsLnav().get(i).prn(),
            "words",m.gpsLnav().get(i).words24()))).toList(),
        "records",m.gpsLnav(),"assistance","GPS LNAV 메타데이터 · I/Q에서 복원한 SFRBX가 아님"));
  }

  static SortedMap<Integer,SortedMap<Integer,double[]>> parse(Path tracking,IqMetadata m) throws IOException {
    var epochs=new TreeMap<Integer,SortedMap<Integer,double[]>>();
    try(var reader=Files.newBufferedReader(tracking)) {
      String line;
      while((line=reader.readLine())!=null) {
        if(!line.startsWith("$IQOBS,")) continue;
        String[] row=line.split(","); if(row.length!=9) throw new IOException("추적 출력 형식 오류");
        double t=Double.parseDouble(row[1]), coff=Double.parseDouble(row[5]);
        int week=Integer.parseInt(row[2]),prn=Integer.parseInt(row[3]),towMs=Integer.parseInt(row[4]);
        double dop=Double.parseDouble(row[6]),phase=Double.parseDouble(row[7]),cno=Double.parseDouble(row[8]);
        if(!Double.isFinite(t) || t<0 || t>=90 || t!=Math.rint(t) || !m.prns().contains(prn)
            || week<0 || week>8191 || towMs<0 || towMs>=604800000 || !Double.isFinite(coff)
            || coff<0 || coff>=.002 || !Double.isFinite(dop) || Math.abs(dop)>20000
            || !Double.isFinite(phase) || !Double.isFinite(cno) || cno<0 || cno>100)
          throw new IOException("추적 관측값 범위 오류");
        // ch->time is the start of a 2 ms correlation; decoded sync TOW is its end.
        double elapsed=(m.week()-week)*604800.0+m.towSeconds()+t-towMs*.001+.002+coff;
        // The tracker wraps millisecond TOW before its next CRC-verified week update.
        if(elapsed>302400) elapsed-=604800;
        if(elapsed< -302400) elapsed+=604800;
        double range=elapsed*299792458.;
        if(range<1e7 || range>5e7) continue; // Ambiguous/invalid frame time is not a usable GPS range.
        var channel=epochs.computeIfAbsent((int)t,k->new TreeMap<>());
        if(channel.put(prn,new double[]{prn,range,dop,cno,phase})!=null) throw new IOException("중복 추적 관측값");
      }
    }
    return epochs;
  }

  /** Compare at tracked time using the simulator's declared constant-velocity model. */
  public static List<Pvt> references(IqMetadata m,List<Pvt> reference,List<Pvt> received) {
    if(reference==null || reference.size()!=1) return List.of();
    Pvt initial=reference.getFirst(); validateReference(initial,m);
    return received.stream().map(p->{
      double dt=(p.getWeek()-m.week())*604800.0+p.getTowSeconds()-m.towSeconds();
      if(!Double.isFinite(dt) || dt<0 || dt>=90) throw new IllegalArgumentException("I/Q 비교 시각 범위 오류");
      Pvt result=new Pvt(); result.setWeek(p.getWeek()); result.setTowSeconds(p.getTowSeconds());
      result.setPositionValid(true); result.setVelocityValid(true);
      double[] position=initial.getEcefMeters().clone();
      for(int i=0;i<3;i++) position[i]+=initial.getVelocityMetersPerSecond()[i]*dt;
      result.setEcefMeters(position); result.setVelocityMetersPerSecond(initial.getVelocityMetersPerSecond().clone());
      result.setReceiverClockBiasSeconds(initial.getReceiverClockBiasSeconds()); result.setSatellitesUsed(initial.getSatellitesUsed());
      result.setMessage("송신 초기 PVT의 등속 궤적 · 동일 샘플 시각"); return result;
    }).toList();
  }

  public static Map<String,Object> comparison(List<Pvt> reference,List<Pvt> received) {
    if(reference.isEmpty() || received.isEmpty()) return Map.of("verdict","INCONCLUSIVE","method",METHOD);
    var measured=new LinkedHashMap<>(DtnComparison.compare(reference,received));
    // No undocumented RF accuracy tolerance: retain differences, not bit-exact PASS/FAIL.
    measured.remove("positionToleranceMeters"); measured.remove("velocityToleranceMetersPerSecond"); measured.remove("clockToleranceSeconds");
    measured.put("verdict",received.stream().anyMatch(Pvt::isPositionValid)?"MEASURED":"INCONCLUSIVE");
    measured.put("method",METHOD); measured.put("message","I/Q 추적 오차 측정 · 합격 허용오차 미설정");
    return measured;
  }

  @PreDestroy public synchronized void close() { closed=true; if(process!=null)process.destroyForcibly(); }
}
