package server.shared.http;

import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Flow;
import org.slf4j.MDC;

/** 원래 HttpClient의 timeout/취소/backpressure를 유지하는 진단 장식자. */
public final class LoggedHttpClient {
    private LoggedHttpClient() {}
    private static final class Call<T> {
        final ApiLog.Exchange log;
        final HttpRequest request;
        final HttpResponse.BodyHandler<T> handler;
        final AtomicReference<HttpResponse.ResponseInfo> info=new AtomicReference<>();
        Call(HttpRequest original,HttpResponse.BodyHandler<T> delegate) {
            String requestId=ApiLog.id(null),traceId=ApiLog.id(MDC.get("apiTraceId"));
            var builder=HttpRequest.newBuilder(original,(k,v)->true)
                .setHeader("X-LNIS-Request-ID",requestId).setHeader("X-LNIS-Trace-ID",traceId);
            var originalBody=original.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody());
            log=new ApiLog.Exchange("OUT",original.method(),original.uri().toString(),original.headers().map(),requestId,traceId);
            if(original.bodyPublisher().isPresent()) builder.method(original.method(),new HttpRequest.BodyPublisher() {
                public long contentLength() { return originalBody.contentLength(); }
                public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
                    originalBody.subscribe(new Flow.Subscriber<ByteBuffer>() {
                        public void onSubscribe(Flow.Subscription s) { downstream.onSubscribe(s); }
                        public void onNext(ByteBuffer item) { log.request.add(item,ApiLog.retain(original.headers().firstValue("Content-Type").orElse(null)));downstream.onNext(item); }
                        public void onError(Throwable e) { downstream.onError(e); }
                        public void onComplete() { downstream.onComplete(); }
                    });
                }
            });
            request=builder.build();
            handler=response->{
                info.set(response);
                var subscriber=delegate.apply(response);
                boolean retain=ApiLog.retain(response.headers().firstValue("Content-Type").orElse(null));
                return new HttpResponse.BodySubscriber<T>() {
                    public CompletionStage<T> getBody() { return subscriber.getBody(); }
                    public void onSubscribe(Flow.Subscription s) { subscriber.onSubscribe(s); }
                    public void onNext(List<ByteBuffer> items) { items.forEach(b->log.response.add(b,retain));subscriber.onNext(items); }
                    public void onError(Throwable e) { subscriber.onError(e); }
                    public void onComplete() { subscriber.onComplete(); }
                };
            };
        }
        void finish(Throwable error) {
            var response=info.get();
            log.finish(response==null?0:response.statusCode(),response==null?Map.of():response.headers().map(),error);
        }
    }
    public static <T> HttpResponse<T> send(HttpClient client,HttpRequest request,HttpResponse.BodyHandler<T> handler)
            throws IOException,InterruptedException {
        var call=new Call<T>(request,handler);
        try { var result=client.send(call.request,call.handler);call.finish(null);return result; }
        catch(IOException|InterruptedException|RuntimeException error) { call.finish(error);throw error; }
    }
    public static <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpClient client,HttpRequest request,HttpResponse.BodyHandler<T> handler) {
        var call=new Call<T>(request,handler);
        try {
            var future=client.sendAsync(call.request,call.handler);
            future.whenComplete((response,error)->call.finish(error));
            return future; // 원래 future를 반환해야 기존 cancel(true)가 실제 HTTP를 취소한다.
        } catch(RuntimeException error) { call.finish(error);throw error; }
    }
}
