package server.central.common;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 응답을 메모리에 전부 올리기 전에 크기를 검사하고 초과 시 수신을 중단한다. */
public final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int maximumBytes;
    private Flow.Subscription subscription;
    private long receivedBytes;
    private boolean failed;

    public LimitedBodySubscriber(int maximumBytes)
    {
        this.maximumBytes = maximumBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody()
    {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription)
    {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> items)
    {
        if (failed) {
            return;
        }
        for (ByteBuffer item : items) {
            receivedBytes += item.remaining();
        }
        if (receivedBytes > maximumBytes) {
            failed = true;
            subscription.cancel();
            delegate.onError(new IOException("상대 노드 응답 크기 초과"));
            return;
        }
        delegate.onNext(items);
    }

    @Override
    public void onError(Throwable error)
    {
        if (!failed) {
            failed = true;
            delegate.onError(error);
        }
    }

    @Override
    public void onComplete()
    {
        if (!failed) {
            delegate.onComplete();
        }
    }
}
