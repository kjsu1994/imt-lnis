package server.shared.http;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** API 양방향 공통 진단. 실제 전송 바이트는 변경하지 않는다. */
@Slf4j
public final class ApiLog {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final int LIMIT=16*1024*1024;
    private static final Map<String,PollState> STATES=Collections.synchronizedMap(new LinkedHashMap<>(128,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,PollState> e) { return size()>2048; }
    });
    private record PollState(String signature,long reportedAt,int suppressed) {}
    record PollDecision(boolean changed,boolean report,int suppressed) {}
    static PollDecision observe(String key,String signature,boolean health,long now) {
        synchronized(STATES) {
            var previous=STATES.get(key);
            boolean changed=previous==null || !signature.equals(previous.signature());
            boolean report=changed || (health && now-previous.reportedAt()>=60_000_000_000L);
            int suppressed=previous==null?0:previous.suppressed();
            STATES.put(key,new PollState(signature,report?now:previous.reportedAt(),report?0:suppressed+1));
            return new PollDecision(changed,report,suppressed);
        }
    }
    private static String causes(Throwable error) {
        var names=new ArrayList<String>();
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        while(error!=null && seen.add(error) && names.size()<16) {
            names.add(error.getClass().getSimpleName());error=error.getCause();
        }
        return String.join(" -> ",names);
    }
    private ApiLog() {}

    public static String id(String value) {
        return value!=null && value.matches("[A-Za-z0-9-]{1,64}")?value:UUID.randomUUID().toString();
    }
    public static String safe(String value) {
        if(value==null) return "";
        return value.replaceAll("[\\r\\n\\t]"," ")
            .replaceAll("(?i)Bearer\\s+[^\\s\"]+","Bearer [hidden]")
            .replaceAll("(?i)(https?://)[^/\\s]+@","$1[hidden]@");
    }
    public static String url(String value) {
        // 쿼리 값에는 토큰·사용자 입력이 들어갈 수 있으므로 이름만 기록한다.
        int query=value.indexOf('?');
        String path=query<0?value:value.substring(0,query);
        if(query<0) return safe(path);
        var names=new ArrayList<String>();
        for(String part:value.substring(query+1).split("&")) names.add(safe(part.split("=",2)[0])+"=[hidden]");
        return safe(path)+"?"+String.join("&",names);
    }
    private static boolean secret(String key) {
        String name=key.toLowerCase(Locale.ROOT).replaceAll("[-_]","");
        return name.contains("authorization") || name.contains("cookie") || name.contains("token")
            || name.contains("password") || name.contains("secret") || name.contains("apikey") || name.contains("credential");
    }
    public static Map<String,Object> headers(Map<String,List<String>> values) {
        var output=new TreeMap<String,Object>(String.CASE_INSENSITIVE_ORDER);
        var allowed=Set.of("content-type","content-length","content-encoding","accept","user-agent","host",
            "range","content-range","content-disposition","etag","digest","x-content-sha256","x-lnis-request-id","x-lnis-trace-id","x-lnis-payload-representation");
        values.forEach((key,value)->output.put(safe(key),secret(key)?"[hidden]":
            allowed.contains(key.toLowerCase(Locale.ROOT))?value.stream().map(ApiLog::safe).toList():"[omitted]"));
        return output;
    }
    private static JsonNode redact(JsonNode value) {
        if(value.isObject()) {
            var node=JSON.createObjectNode();
            value.fields().forEachRemaining(e->node.set(e.getKey(),secret(e.getKey())?TextNode.valueOf("[hidden]"):redact(e.getValue())));
            return node;
        }
        if(value.isArray()) { var node=JSON.createArrayNode(); value.forEach(v->node.add(redact(v))); return node; }
        return value.isTextual()?TextNode.valueOf(safe(value.asText())):value;
    }
    private static JsonNode tree(Capture body) {
        if(body.truncated()) return null;
        try { return JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body.bytes()); }
        catch(Exception ignored) { return null; }
    }
    private static String body(Capture capture) {
        if(capture.size()==0) return "[empty or not consumed]";
        if(capture.truncated()) return "[body exceeds logging limit; omitted, bytes="+capture.size()+"]";
        var node=tree(capture);
        if(node==null) return "[non-JSON/streaming body omitted; bytes="+capture.size()+"]";
        try { return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(redact(node)); }
        catch(Exception ignored) { return "[body unavailable]"; }
    }
    private static String state(JsonNode node) {
        if(node==null) return "";
        var values=new ArrayList<String>(); collectState(node,"",values); return values.toString();
    }
    private static void collectState(JsonNode node,String path,List<String> values) {
        if(values.size()>200) return;
        if(node.isArray()) { for(int i=0;i<node.size();i++) collectState(node.get(i),path+"/"+i,values); }
        else if(node.isObject()) node.fields().forEachRemaining(e->{
            if(Set.of("state","status","online","ready","ok","accepted","peerOnline").contains(e.getKey()) && e.getValue().isValueNode())
                values.add(path+"/"+e.getKey()+"="+safe(e.getValue().asText()));
            else if(e.getValue().isContainerNode()) collectState(e.getValue(),path+"/"+e.getKey(),values);
        });
    }

    public static final class Exchange {
        public final String requestId, traceId;
        private final String direction, method, address;
        private final Map<String,List<String>> requestHeaders;
        private final long started=System.nanoTime();
        public final Capture request=new Capture(), response=new Capture();
        public Exchange(String direction,String method,String address,Map<String,List<String>> headers,String requestId,String traceId) {
            this.direction=direction;this.method=method;this.address=url(address);this.requestHeaders=headers;
            this.requestId=id(requestId);this.traceId=id(traceId==null?this.requestId:traceId);
            ("GET".equals(method)?log.atDebug():log.atInfo()).log("API_START direction={} requestId={} traceId={} method={} url={} headers={}\n",
                direction,this.requestId,this.traceId,method,this.address,ApiLog.headers(headers));
        }
        public void finish(int status,Map<String,List<String>> headers,Throwable error) {
            try {
                var requestTree=tree(request); var responseTree=tree(response);
                String testId=requestTree==null?"":safe(requestTree.path("testId").asText(""));
                if(testId.isEmpty() && responseTree!=null) testId=safe(responseTree.path("testId").asText(""));
                long elapsed=(System.nanoTime()-started)/1_000_000;
                String cause=causes(error);
                String signature=status+":"+state(responseTree)+":"+cause;
                String path=address.split("\\?",2)[0];
                boolean health="GET".equals(method) && (path.endsWith("/sender/health") || path.endsWith("/receiver/health") || path.endsWith("/dtn/adapter-health"));
                boolean listing="GET".equals(method) && (path.endsWith("/dtn/tests") || path.endsWith("/dtn/receipts"));
                String key=direction+":"+method+":"+address;
                var decision=observe(key,signature,health && (error!=null || status>=400),System.nanoTime());
                boolean changed=decision.changed();
                boolean quietHealth=health && !decision.report();
                boolean detailed=!address.contains("/logs/screen") && (!"GET".equals(method) || status>=400 || error!=null || changed);
                var summary=quietHealth?log.atDebug():status>=500?log.atError():status>=400 || error!=null?log.atWarn():
                    "GET".equals(method) && !changed && !(health && decision.report())?log.atDebug():log.atInfo();
                summary.log("API_END direction={} requestId={} traceId={} testId={} method={} url={} status={} elapsedMs={} requestBytes={} responseBytes={} responseHeaders={} cause={} suppressed={} itemCount={}\n",
                    direction,requestId,traceId,testId,method,address,status,elapsed,request.size(),response.size(),ApiLog.headers(headers),cause,decision.suppressed(),responseTree!=null && responseTree.isArray()?responseTree.size():null);
                if(detailed && (request.size()>0 || response.size()>0))
                    (quietHealth || (listing && status<400 && error==null)?log.atDebug():log.atInfo()).log("API_BODY requestId={} traceId={}\nREQUEST\n{}\nRESPONSE\n{}\nAPI_BODY END requestId={}\n",
                    requestId,traceId,body(request),body(response),requestId);
                // 예외 메시지는 요청 URL/자격증명을 포함할 수 있어 공통 계층에서는 유형과 위치만 남긴다.
                if(error!=null) log.debug("API_FAILURE requestId={} cause={} stack={}\n",requestId,cause,Arrays.toString(error.getStackTrace()));
            } catch(RuntimeException loggingError) {
                log.warn("API_LOG_FAILURE requestId={} type={}\n",requestId,loggingError.getClass().getSimpleName());
            }
        }
    }

    public static final class Capture {
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private long count;
        public synchronized void add(byte[] bytes,int offset,int length,boolean retain) {
            count+=length;
            if(retain && output.size()<LIMIT) output.write(bytes,offset,Math.min(length,LIMIT-output.size()));
        }
        public synchronized void add(ByteBuffer bytes,boolean retain) {
            var copy=bytes.duplicate(); int length=copy.remaining(); count+=length;
            if(retain && output.size()<LIMIT) {
                byte[] part=new byte[Math.min(length,LIMIT-output.size())]; copy.get(part); output.writeBytes(part);
            }
        }
        public synchronized byte[] bytes() { return output.toByteArray(); }
        public synchronized long size() { return count; }
        public synchronized boolean truncated() { return count>LIMIT && output.size()>0; }
        public synchronized void reset() { output.reset();count=0; }
    }
    public static boolean retain(String type) {
        return type==null || type.toLowerCase(Locale.ROOT).contains("json") || type.toLowerCase(Locale.ROOT).startsWith("text/plain");
    }
}
