package server.central.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import server.shared.http.ApiLog;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 읽은 요청/쓴 응답만 복제한다. 다운로드 및 비동기 응답을 전체 버퍼링하지 않는다. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+10)
public class ApiLoggingFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/lnis/api/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var headers=new LinkedHashMap<String,List<String>>();
        Collections.list(request.getHeaderNames()).forEach(name->headers.put(name,Collections.list(request.getHeaders(name))));
        String address=request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString());
        var exchange=new ApiLog.Exchange("IN",request.getMethod(),address,headers,
            request.getHeader("X-LNIS-Request-ID"),request.getHeader("X-LNIS-Trace-ID"));
        response.setHeader("X-LNIS-Request-ID",exchange.requestId);
        response.setHeader("X-LNIS-Trace-ID",exchange.traceId);
        var wrappedResponse=new Response(response,exchange.response);
        var wrappedRequest=new Request(request,wrappedResponse,exchange.request);
        var done=new AtomicBoolean();
        java.util.function.Consumer<Throwable> finish=error->{
            if(!done.compareAndSet(false,true)) return;
            var resultHeaders=new LinkedHashMap<String,List<String>>();
            response.getHeaderNames().forEach(name->resultHeaders.put(name,new ArrayList<>(response.getHeaders(name))));
            exchange.finish(error!=null && response.getStatus()<400?500:response.getStatus(),resultHeaders,error);
        };
        String previous=MDC.get("apiTraceId");MDC.put("apiTraceId",exchange.traceId);
        Throwable failure=null;
        try { chain.doFilter(wrappedRequest,wrappedResponse); }
        catch(IOException|ServletException|RuntimeException error) { failure=error;throw error; }
        finally {
            if(previous==null) MDC.remove("apiTraceId");else MDC.put("apiTraceId",previous);
            if(request.isAsyncStarted() && failure==null) {
                try {
                    request.getAsyncContext().addListener(new AsyncListener() {
                        public void onComplete(AsyncEvent event) { finish.accept(null); }
                        public void onError(AsyncEvent event) { finish.accept(event.getThrowable()); }
                        public void onTimeout(AsyncEvent event) { finish.accept(new java.net.http.HttpTimeoutException("Async API timeout")); }
                        public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                    });
                } catch(IllegalStateException completed) { finish.accept(null); }
            } else finish.accept(failure);
        }
    }
    private static final class Request extends HttpServletRequestWrapper {
        private final HttpServletResponse response;
        private final ApiLog.Capture capture;
        private ServletInputStream stream;
        Request(HttpServletRequest request,HttpServletResponse response,ApiLog.Capture capture) {
            super(request);this.response=response;this.capture=capture;
        }
        @Override public ServletInputStream getInputStream() throws IOException {
            if(stream==null) {
                var original=super.getInputStream();
                boolean retain=ApiLog.retain(getContentType());
                stream=new ServletInputStream() {
                    public boolean isFinished(){return original.isFinished();}
                    public boolean isReady(){return original.isReady();}
                    public void setReadListener(ReadListener listener){original.setReadListener(listener);}
                    public int read() throws IOException {int value=original.read();if(value>=0) capture.add(new byte[]{(byte)value},0,1,retain);return value;}
                    public int read(byte[] bytes,int offset,int length) throws IOException {int read=original.read(bytes,offset,length);if(read>0) capture.add(bytes,offset,read,retain);return read;}
                    public void close() throws IOException {original.close();}
                };
            }
            return stream;
        }
        @Override public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(),getCharacterEncoding()==null?"UTF-8":getCharacterEncoding()));
        }
        @Override public AsyncContext startAsync() { return super.startAsync(this,response); }
    }
    private static final class Response extends HttpServletResponseWrapper {
        private final ApiLog.Capture capture;
        private ServletOutputStream stream;
        private PrintWriter writer;
        Response(HttpServletResponse response,ApiLog.Capture capture){super(response);this.capture=capture;}
        @Override public ServletOutputStream getOutputStream() throws IOException {
            if(stream==null) {
                var original=super.getOutputStream();
                stream=new ServletOutputStream() {
                    public boolean isReady(){return original.isReady();}
                    public void setWriteListener(WriteListener listener){original.setWriteListener(listener);}
                    public void write(int value) throws IOException {original.write(value);capture.add(new byte[]{(byte)value},0,1,ApiLog.retain(getContentType()));}
                    public void write(byte[] bytes,int offset,int length) throws IOException {original.write(bytes,offset,length);capture.add(bytes,offset,length,ApiLog.retain(getContentType()));}
                    public void flush() throws IOException {original.flush();}
                    public void close() throws IOException {original.close();}
                };
            }
            return stream;
        }
        @Override public PrintWriter getWriter() throws IOException {
            if(writer==null) {
                var original=super.getWriter();
                writer=new PrintWriter(new Writer() {
                    public void write(char[] chars,int offset,int length) throws IOException {
                        original.write(chars,offset,length);
                        byte[] bytes=new String(chars,offset,length).getBytes(getCharacterEncoding());
                        capture.add(bytes,0,bytes.length,ApiLog.retain(getContentType()));
                    }
                    public void flush(){original.flush();}
                    public void close(){original.close();}
                });
            }
            return writer;
        }
        @Override public void sendError(int status) throws IOException {super.sendError(status);capture.reset();}
        @Override public void sendError(int status,String message) throws IOException {super.sendError(status,message);capture.reset();}
        @Override public void resetBuffer(){super.resetBuffer();capture.reset();}
        @Override public void reset(){super.reset();capture.reset();writer=null;stream=null;}
    }
}
