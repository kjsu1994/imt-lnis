package server.gnss;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Windows process exposing byte I/O, never PVT or application data. */
public final class WindowsSerialBridge implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService handlers = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper json = new ObjectMapper();
    private final String token;
    private final String version = System.getProperty("lnis.bridge.version", "development");
    private final TimeReference time = new TimeReference();
    private UbloxParser timeParser = new UbloxParser();
    private volatile SerialConnection port;
    private String session;
    private long touched;
    private final long leaseNanos;
    private final java.util.function.Function<SerialCaptureService.Settings, SerialConnection> opener;
    private final java.util.function.Supplier<List<SerialCaptureService.DetectedPort>> ports;

    public WindowsSerialBridge(InetSocketAddress address, String token) throws java.io.IOException {
        this(address, token, 30_000);
    }

    WindowsSerialBridge(InetSocketAddress address, String token, long leaseMillis) throws java.io.IOException {
        this(address, token, leaseMillis, SerialConnection::local, SerialConnection::localPorts);
    }

    WindowsSerialBridge(InetSocketAddress address, String token, long leaseMillis,
            java.util.function.Function<SerialCaptureService.Settings, SerialConnection> opener,
            java.util.function.Supplier<List<SerialCaptureService.DetectedPort>> ports) throws java.io.IOException {
        this.opener = opener;
        this.ports = ports;
        if (token == null || token.length() < 32) throw new IllegalArgumentException("Bridge token must have at least 32 characters");
        this.token = token;
        leaseNanos = TimeUnit.MILLISECONDS.toNanos(leaseMillis);
        server = HttpServer.create(address, 8);
        server.setExecutor(handlers);
        server.createContext("/", this::handle);
        expiry.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    public void start() { server.start(); }
    int portNumber() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        String receivedAt = java.time.Instant.now().toString();
        try (exchange) {
            int code = 200;
            Object result;
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                    auth.getBytes(StandardCharsets.UTF_8))) {
                code = 401; result = Map.of("error", "Unauthorized");
            } else if (!exchange.getRequestMethod().equals("POST")) {
                code = 405; result = Map.of("error", "POST required");
            } else {
                byte[] body = exchange.getRequestBody().readNBytes(20001);
                if (body.length > 20000) { code = 413; result = Map.of("error", "Request too large"); }
                else try {
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals("/time")) {
                        var reading = time.reading();
                        var value = new LinkedHashMap<String, Object>();
                        value.put("receivedAt", receivedAt);
                        value.put("sentAt", java.time.Instant.now().toString());
                        value.put("ready", port != null && reading.ready());
                        value.put("utc", reading.utc() == null ? null : reading.utc().toString());
                        value.put("samples", reading.samples());
                        value.put("spreadSeconds", reading.spreadSeconds());
                        result = value;
                    } else {
                        result = dispatch(path, json.readTree(body));
                    }
                }
                catch (IllegalArgumentException e) { code = 400; result = Map.of("error", e.getMessage()); }
                catch (Exception e) { code = 409; result = Map.of("error", Objects.toString(e.getMessage(), "COM bridge error")); }
            }
            byte[] response = json.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private synchronized Object dispatch(String path, JsonNode body) throws Exception {
        if (body == null) throw new IllegalArgumentException("JSON required");
        if (path.equals("/health")) {
            return Map.of("status", "UP", "busy", port != null, "version", version);
        }
        if (path.equals("/ports")) return ports.get();
        if (path.equals("/open")) {
            expire();
            if (port != null) throw new IllegalStateException("다른 수집이 COM 포트를 사용 중입니다.");
            var settings = json.treeToValue(body, SerialCaptureService.Settings.class);
            if (settings.portName() == null || !settings.portName().matches("COM[1-9][0-9]*")
                    || settings.baudRate() < 300 || settings.baudRate() > 4_000_000)
                throw new IllegalArgumentException("Invalid Windows COM port or baud rate");
            if (ports.get().stream().noneMatch(p -> p.name().equals(settings.portName())))
                throw new IllegalStateException("연결된 Windows 포트가 아닙니다: " + settings.portName());
            port = opener.apply(settings);
            time.reset();
            timeParser = new UbloxParser();
            session = UUID.randomUUID().toString(); touched = System.nanoTime();
            return Map.of("session", session);
        }
        if (port == null || !Objects.equals(session, body.path("session").asText()))
            throw new IllegalStateException("COM 수집 세션이 종료되었거나 일치하지 않습니다.");
        touched = System.nanoTime();
        try {
            return switch (path) {
                case "/read" -> {
                    int length = body.path("length").asInt();
                    if (length < 1 || length > 8192) throw new IllegalArgumentException("Invalid read length");
                    byte[] data = new byte[length];
                    int count = port.readBytes(data, length);
                    if (count < 0) throw new IllegalStateException("COM 연결이 끊겼습니다.");
                    long receivedTick = System.nanoTime();
                    for (var frame : timeParser.push(data, count)) {
                        time.observe(frame, receivedTick);
                    }
                    yield Map.of("data", Base64.getEncoder().encodeToString(Arrays.copyOf(data, count)));
                }
                case "/write" -> {
                    byte[] data = Base64.getDecoder().decode(body.path("data").asText());
                    if (data.length > 8192) throw new IllegalArgumentException("Write too large");
                    int count = port.writeBytes(data, data.length);
                    if (count != data.length) throw new IllegalStateException("COM 쓰기에 실패했습니다.");
                    yield Map.of("count", count);
                }
                case "/close" -> { release(); yield Map.of("closed", true); }
                default -> throw new IllegalArgumentException("Unknown bridge operation");
            };
        } catch (IllegalStateException e) { release(); throw e; }
    }

    private synchronized void expire() {
        if (port != null && System.nanoTime() - touched > leaseNanos) release();
    }
    private void release() {
        try { if (port != null) port.closePort(); }
        finally { port = null; session = null; time.reset(); }
    }
    public synchronized void close() {
        release(); expiry.shutdownNow(); server.stop(0); handlers.shutdownNow();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: WindowsSerialBridge config.properties");
        var props = new Properties();
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(args[0]))) { props.load(input); }
        var bridge = new WindowsSerialBridge(new InetSocketAddress(props.getProperty("bind", "127.0.0.1"),
            Integer.parseInt(props.getProperty("port", "18765"))), props.getProperty("token"));
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::close));
        bridge.start();
        System.out.println("Windows COM bridge ready on " + props.getProperty("bind") + ":" + bridge.portNumber());
    }
}
