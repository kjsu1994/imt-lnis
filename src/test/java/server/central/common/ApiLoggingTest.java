package server.central.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import server.shared.http.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;

class ApiLoggingTest {
    private static class Logs implements AutoCloseable {
        final Logger logger=(Logger)LoggerFactory.getLogger(ApiLog.class);
        final Level previous=logger.getLevel();
        final ListAppender<ILoggingEvent> appender=new ListAppender<>();
        Logs(){appender.start();logger.addAppender(appender);logger.setLevel(Level.INFO);}
        String text(){return appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);}
        public void close(){logger.detachAppender(appender);logger.setLevel(previous);appender.stop();}
    }
    @Test void inboundPreservesBytesAndMasksHeadersQueryAndNestedSecrets() throws Exception {
        try(var logs=new Logs()) {
            var request=new MockHttpServletRequest("POST","/lnis/api/v1/dtn/receive");
            request.setContentType("application/json");request.addHeader("Authorization","Bearer inbound-secret");
            request.addHeader("Cookie","session=private-cookie");request.setQueryString("token=private-query");
            byte[] payload="{\"testId\":\"trial\",\"nested\":{\"password\":\"private-password\"},\"value\":3}".getBytes(StandardCharsets.UTF_8);
            request.setContent(payload);var response=new MockHttpServletResponse();
            byte[] output="{\"accepted\":true,\"apiKey\":\"private-response\"}".getBytes(StandardCharsets.UTF_8);
            new ApiLoggingFilter().doFilter(request,response,(req,res)->{
                assertArrayEquals(payload,req.getInputStream().readAllBytes());
                res.setContentType("application/json");res.getOutputStream().write(output);
            });
            assertArrayEquals(output,response.getContentAsByteArray());
            assertNotNull(response.getHeader("X-LNIS-Request-ID"));
            String text=logs.text();assertTrue(text.contains("API_BODY"));assertTrue(text.contains("trial"));
            for(String secret:List.of("inbound-secret","private-cookie","private-query","private-password","private-response")) assertFalse(text.contains(secret),secret);
        }
    }
    @Test void binaryResponseIsStreamedWithoutBodyDisclosureAndWriterStillWorks() throws Exception {
        try(var logs=new Logs()) {
            var request=new MockHttpServletRequest("GET","/lnis/api/v1/files/1");
            var response=new MockHttpServletResponse();byte[] binary="BINARY_PRIVATE_DATA".repeat(1000).getBytes(StandardCharsets.UTF_8);
            new ApiLoggingFilter().doFilter(request,response,(req,res)->{
                res.setContentType("application/octet-stream");res.getOutputStream().write(binary);res.flushBuffer();
                assertEquals(binary.length,response.getContentAsByteArray().length,"bytes reach client before filter finishes");
            });
            assertArrayEquals(binary,response.getContentAsByteArray());assertFalse(logs.text().contains("BINARY_PRIVATE_DATA"));
            var writerResponse=new MockHttpServletResponse();writerResponse.setCharacterEncoding("UTF-8");
            new ApiLoggingFilter().doFilter(new MockHttpServletRequest("GET","/lnis/api/v1/writer"),writerResponse,(req,res)->{
                res.setContentType("application/json");res.getWriter().write("{\"status\":\"ready\"}");
            });
            assertEquals("{\"status\":\"ready\"}",writerResponse.getContentAsString());
        }
    }
    @Test void pollingRemainsDiagnosticEvenWhenResponseStateChanges() throws Exception {
        try(var logs=new Logs()) {
            logs.logger.setLevel(Level.DEBUG);
            String path="/lnis/api/v1/poll/"+UUID.randomUUID();
            for(String state:List.of("ready","ready","busy")) new ApiLoggingFilter().doFilter(
                new MockHttpServletRequest("GET",path),new MockHttpServletResponse(),(req,res)->{
                    res.setContentType("application/json");res.getOutputStream().write(("{\"status\":\""+state+"\"}").getBytes(StandardCharsets.UTF_8));
                });
            assertEquals(3,logs.appender.list.stream().filter(e->e.getFormattedMessage().startsWith("API_START") && e.getLevel()==Level.DEBUG).count());
            var ends=logs.appender.list.stream().filter(e->e.getFormattedMessage().startsWith("API_END")).toList();
            assertEquals(List.of(Level.DEBUG,Level.DEBUG,Level.DEBUG),ends.stream().map(ILoggingEvent::getLevel).toList());
            assertEquals(3,logs.appender.list.stream().filter(e->e.getFormattedMessage().startsWith("API_BODY")).count());
        }
    }
    @Test void repeatedFailuresAndTransfersRemainVisible() {
        try(var logs=new Logs()) {
            String path="/lnis/api/v1/verify/"+UUID.randomUUID();
            for(int status:List.of(200,200,400,400,500,200))
                new ApiLog.Exchange("IN","GET",path,Map.of(),null,null).finish(status,Map.of(),null);
            for(int i=0;i<2;i++)
                new ApiLog.Exchange("OUT","POST",path,Map.of(),null,null).finish(202,Map.of(),null);
            var ends=logs.appender.list.stream().filter(e->e.getFormattedMessage().startsWith("API_END")).toList();
            assertEquals(List.of(Level.WARN,Level.WARN,Level.ERROR,Level.INFO,Level.INFO),
                ends.stream().map(ILoggingEvent::getLevel).toList());
            assertEquals(0,logs.appender.list.stream().filter(e->e.getFormattedMessage().startsWith("API_START")).count());
        }
    }
    @Test void listBodiesStayDebugAndEveryHealthFailureIsVisible() {
        try(var logs=new Logs()) {
            String base="http://"+UUID.randomUUID()+"/";
            for(String name:List.of("tests","receipts")) {
                var call=new ApiLog.Exchange("IN","GET",base+"dtn/"+name,Map.of(),null,null);
                byte[] data="[{\"testId\":\"OLD_HISTORY\",\"state\":\"FAILED\"}]".getBytes(StandardCharsets.UTF_8);
                call.response.add(data,0,data.length,true);call.finish(200,Map.of(),null);
            }
            for(int i=0;i<3;i++) new ApiLog.Exchange("OUT","GET",base+"sender/health",Map.of(),null,null)
                .finish(0,Map.of(),new CompletionException(new java.net.ConnectException()));
            new ApiLog.Exchange("OUT","GET",base+"sender/health",Map.of(),null,null).finish(200,Map.of(),null);
            assertFalse(logs.text().contains("OLD_HISTORY"));
            assertFalse(logs.text().contains("API_BODY"));
            assertFalse(logs.text().contains("API_FAILURE"));
            assertEquals(3,logs.appender.list.stream().filter(e->e.getLevel()==Level.WARN).count());
            assertTrue(logs.text().contains("ConnectException"));
            assertFalse(logs.text().contains("suppressed="));
        }
    }
    @Test void outboundPreservesBodiesAndAuthenticationWhileLoggingSafely() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] payload="{\"testId\":\"out-trial\",\"sendToken\":\"outgoing-private\"}".getBytes(StandardCharsets.UTF_8);
        var arrived=new CompletableFuture<String>();
        server.createContext("/transfers",exchange->{try(exchange){
            arrived.complete(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body=exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(202,body.length);exchange.getResponseBody().write(body);
        }});server.start();
        try(var logs=new Logs()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/transfers"))
                .header("Content-Type","application/json").header("Authorization","Bearer original-auth")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            var response=LoggedHttpClient.send(HttpClient.newHttpClient(),request,HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202,response.statusCode());assertArrayEquals(payload,response.body());
            assertEquals("Bearer original-auth",arrived.get(3,TimeUnit.SECONDS));
            assertTrue(logs.text().contains("direction=OUT"));assertTrue(logs.text().contains("out-trial"));
            assertFalse(logs.text().contains("original-auth"));assertFalse(logs.text().contains("outgoing-private"));
        } finally {server.stop(0);}
    }
    @Test void cancellingLoggedHttpFutureStillCancelsTheRequest() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/slow",exchange->{try(exchange){entered.countDown();
            try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }});server.start();
        try(var logs=new Logs()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/slow")).GET().build();
            var future=LoggedHttpClient.sendAsync(HttpClient.newHttpClient(),request,HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(entered.await(10,TimeUnit.SECONDS),"request reached server");
            assertTrue(future.cancel(true),"original HTTP future accepts cancellation");
            assertTrue(future.isCompletedExceptionally());
            // HttpClient can wrap cancellation in CompletionException during transport shutdown.
            Throwable cancelled=assertThrows(RuntimeException.class,future::join);
            while(!(cancelled instanceof CancellationException) && cancelled.getCause()!=null) cancelled=cancelled.getCause();
            assertInstanceOf(CancellationException.class,cancelled);
            assertTrue(logs.text().contains("CancellationException"));
        } finally {release.countDown();server.stop(0);}
    }

    @Test void asynchronousCompletionLogsOnce() throws Exception {
        try(var logs=new Logs()) {
            logs.logger.setLevel(Level.DEBUG);
            var request=new MockHttpServletRequest("GET","/lnis/api/v1/async");request.setAsyncSupported(true);
            var response=new MockHttpServletResponse();
            new ApiLoggingFilter().doFilter(request,response,(req,res)->{
                req.startAsync();res.setContentType("application/json");res.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            });
            assertFalse(logs.text().contains("API_END"));
            request.getAsyncContext().complete();
            assertEquals(1,logs.appender.list.stream().filter(e->e.getFormattedMessage().startsWith("API_END")).count());
        }
    }

    @Test void routeShowsActualEndpointsAndMappingWithoutControllerNames() throws Exception {
        try (var logs = new Logs()) {
            var request = new MockHttpServletRequest("POST", "/lnis/api/v1/sessions/123/evidence");
            request.setServerName("192.168.1.72");
            request.setServerPort(8089);
            request.setRemoteAddr("192.168.1.154");
            request.setRemotePort(50123);
            request.setLocalAddr("172.20.0.2");
            request.setLocalPort(8089);
            var response = new MockHttpServletResponse();
            new ApiLoggingFilter().doFilter(request, response, (req, res) -> {
                req.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        "/lnis/api/v1/sessions/{id}/evidence");
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(202);
            });
            String text = logs.text();
            assertTrue(text.contains("method=POST"));
            assertTrue(text.contains("url=http://192.168.1.72:8089/lnis/api/v1/sessions/123/evidence"));
            assertTrue(text.contains("peer=192.168.1.154:50123"));
            assertTrue(text.contains("local=172.20.0.2:8089"));
            assertTrue(text.contains("mapping=/lnis/api/v1/sessions/{id}/evidence"));
            assertFalse(text.contains("handler="));
            assertFalse(text.contains("API_START"));
        }
    }

    @Test void reportAndScreenTransportDoNotReplayDetailsAtInfo() throws Exception {
        try (var logs = new Logs()) {
            for (String path : List.of("/lnis/api/v1/dtn/tests/123/report", "/lnis/api/v1/dtn/logs")) {
                var request = new MockHttpServletRequest("GET", path);
                var response = new MockHttpServletResponse();
                byte[] body = "{\"status\":\"FAILED\",\"message\":\"OLD_REPORT_DETAIL\"}".getBytes(StandardCharsets.UTF_8);
                new ApiLoggingFilter().doFilter(request, response, (req, res) -> {
                    res.setContentType("application/json");
                    res.getOutputStream().write(body);
                });
                assertArrayEquals(body, response.getContentAsByteArray());
            }
            for (String path : List.of("/lnis/api/v1/logs/screen", "/lnis/api/v1/dtn/logs/screen")) {
                new ApiLog.Exchange("IN", "POST", path, Map.of(), null, null).finish(204, Map.of(), null);
            }
            assertEquals("", logs.text());
            new ApiLog.Exchange("IN", "POST", "/lnis/api/v1/logs/screen", Map.of(), null, null)
                    .finish(400, Map.of(), null);
            assertEquals(1, logs.appender.list.size());
            assertEquals(Level.WARN, logs.appender.list.getFirst().getLevel());
        }
    }

    @Test void rejectedOriginalIsPrettyPrintedOnceWithoutEmptyResponseSection() throws Exception {
        try (var logs = new Logs()) {
            var request = new MockHttpServletRequest("POST", "/lnis/api/v1/dtn/receive");
            request.setContentType("application/json");
            byte[] body = "{\"testId\":\"rejected\",\"value\":42}".getBytes(StandardCharsets.UTF_8);
            request.setContent(body);
            var response = new MockHttpServletResponse();
            new ApiLoggingFilter().doFilter(request, response, (req, res) -> {
                assertArrayEquals(body, req.getInputStream().readAllBytes());
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(400);
            });
            assertEquals(1, logs.appender.list.stream().filter(e -> e.getFormattedMessage().startsWith("API_BODY ")).count());
            assertTrue(logs.text().contains("\n  \"value\" : 42"));
            assertFalse(logs.text().contains("RESPONSE\n"));
            assertTrue(logs.text().contains("API_BODY END requestId="));
        }
    }
}
