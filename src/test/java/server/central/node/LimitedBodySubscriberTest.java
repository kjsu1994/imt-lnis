package server.central.node;
import server.central.common.LimitedBodySubscriber;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LimitedBodySubscriberTest {
    @Test
    void returnsBodyWithinLimit()
    {
        LimitedBodySubscriber subscriber = new LimitedBodySubscriber(3);
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        subscriber.onComplete();
        assertArrayEquals(new byte[] {1, 2, 3}, subscriber.getBody().toCompletableFuture().join());
    }

    @Test
    void cancelsOversizedResponseBeforeAccumulatingIt()
    {
        LimitedBodySubscriber subscriber = new LimitedBodySubscriber(3);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {3, 4})));
        subscriber.onComplete();
        verify(subscription).cancel();
        assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    }
}
