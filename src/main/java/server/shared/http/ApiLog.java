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
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    public static final int LIMIT=16*1024*1024;
    // Only connection transitions need state; report/list payloads are never compared.
    private static final Map<String, String> HEALTH_STATES = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > 2048;
        }
    };

    static boolean connectionChanged(String key, String signature) {
        synchronized (HEALTH_STATES) {
            return !Objects.equals(HEALTH_STATES.put(key, signature), signature);
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
        if (value == null) return "";
        return value.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("(?i)Bearer\\s+[^\\s\"]+", "Bearer [hidden]")
                .replaceAll("(?i)(https?://)[^/\\s]+@", "$1[hidden]@")
                .replaceAll("(?i)(?<![a-z0-9_-])([\"']?(?:authorization|cookie|[a-z0-9_-]{0,64}(?:token|password|secret|apikey|credential)[a-z0-9_-]{0,64})[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\]]+)", "$1[hidden]");
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
    /** Same secret masking for structured application events as for HTTP JSON. */
    public static String eventBody(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(redact(JSON.valueToTree(value)));
        } catch (Exception ignored) {
            return "[event details unavailable]";
        }
    }

    private static JsonNode tree(Capture body) {
        if (!body.enabled || body.size() == 0 || body.truncated()) return null;
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
        public final Capture request, response;
        private final String direction, method, address;
        private final boolean diagnostic, connection, screen, transfer;
        private final long started = System.nanoTime();
        private String route = "";

        /** Servlet mapping resolution happens after the filter starts; keep it separate from the concrete URL. */
        public void inboundRoute(String peer, String local, String mapping) {
            route = " peer=" + safe(peer) + " local=" + safe(local)
                    + " mapping=" + safe(mapping);
        }

        public Exchange(String direction, String method, String address,
                Map<String, List<String>> headers, String requestId, String traceId) {
            this.direction = direction;
            this.method = method;
            this.address = url(address);
            this.requestId = id(requestId);
            this.traceId = id(traceId == null ? this.requestId : traceId);

            String path = this.address.split("\\?", 2)[0];
            diagnostic = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
            connection = "GET".equals(method) && (path.endsWith("/sender/health")
                    || path.endsWith("/receiver/health") || path.endsWith("/dtn/adapter-health")
                    || path.endsWith("/node/peer/status"));
            screen = path.endsWith("/logs/screen");
            transfer = "POST".equals(method) && (("OUT".equals(direction) && path.endsWith("/transfers"))
                    || ("IN".equals(direction) && path.endsWith("/dtn/receive")));
            boolean retainBody = log.isDebugEnabled() || transfer || (!diagnostic && !screen);
            request = new Capture(retainBody);
            response = new Capture(retainBody || connection);

            if (log.isDebugEnabled()) {
                log.debug("API_START direction={} requestId={} traceId={} method={} url={} headers={}",
                        direction, this.requestId, this.traceId, method, this.address, ApiLog.headers(headers));
            }
        }

        public void finish(int status, Map<String, List<String>> headers, Throwable error) {
            try {
                JsonNode requestTree = tree(request);
                JsonNode responseTree = tree(response);
                String testId = requestTree == null ? "" : safe(requestTree.path("testId").asText(""));
                if (testId.isEmpty() && responseTree != null) {
                    testId = safe(responseTree.path("testId").asText(""));
                }
                String context = testId.isEmpty() ? "" : " testId=" + testId;
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                String cause = causes(error);
                boolean failed = error != null || status >= 400;
                boolean changed = connection && connectionChanged(direction + ":" + address,
                        status + ":" + state(responseTree) + ":" + cause);
                // Never suppress a warning, including repeated background connection failures.
                var summary = status >= 500 ? log.atError() : failed ? log.atWarn()
                        : (!diagnostic && !screen) || changed ? log.atInfo() : log.atDebug();
                summary.log("API_END direction={} requestId={} traceId={}{} method={} url={} status={} elapsedMs={} requestBytes={} responseBytes={}{}{}",
                        direction, requestId, traceId, context, method, address, status, elapsed,
                        request.size(), response.size(), cause.isEmpty() ? "" : " cause=" + cause, route);

                if (log.isDebugEnabled()) {
                    log.debug("API_HEADERS requestId={} responseHeaders={}", requestId, ApiLog.headers(headers));
                }
                if (!screen && (transfer || log.isDebugEnabled()) && (request.size() > 0 || response.size() > 0)) {
                    StringBuilder block = new StringBuilder();
                    if (request.size() > 0) block.append("REQUEST\n").append(body(request)).append('\n');
                    if (response.size() > 0) block.append("RESPONSE\n").append(body(response)).append('\n');
                    (transfer ? log.atInfo() : log.atDebug()).log(
                            "API_BODY direction={} requestId={} traceId={}{} method={} url={}{}\n{}API_BODY END requestId={}\n",
                            direction, requestId, traceId, context, method, address, route, block, requestId);
                }
                if (error != null) {
                    // Expected transport errors have a compact WARN summary; internal failures keep a stack.
                    boolean internal = "IN".equals(direction) && status >= 500
                            && !(error instanceof java.io.IOException);
                    if (internal || log.isDebugEnabled()) {
                        var output = new java.io.StringWriter();
                        error.printStackTrace(new java.io.PrintWriter(output));
                        String stack = output.toString().lines().map(ApiLog::safe)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        (internal ? log.atError() : log.atDebug()).log(
                                "API_FAILURE requestId={} cause={}\n{}\nAPI_FAILURE END requestId={}\n",
                                requestId, cause, stack, requestId);
                    }
                }
            } catch (RuntimeException loggingError) {
                log.warn("API_LOG_FAILURE requestId={} type={}", requestId, loggingError.getClass().getSimpleName());
            }
        }
    }

    public static final class Capture {
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private final boolean enabled;
        public Capture() { this(true); }
        Capture(boolean enabled) { this.enabled = enabled; }
        private long count;
        public synchronized void add(byte[] bytes,int offset,int length,boolean retain) {
            count+=length;
            if(enabled && retain && output.size()<LIMIT) output.write(bytes,offset,Math.min(length,LIMIT-output.size()));
        }
        public synchronized void add(ByteBuffer bytes,boolean retain) {
            var copy=bytes.duplicate(); int length=copy.remaining(); count+=length;
            if(enabled && retain && output.size()<LIMIT) {
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
