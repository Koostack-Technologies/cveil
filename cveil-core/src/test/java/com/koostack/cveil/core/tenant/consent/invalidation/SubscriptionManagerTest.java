package com.koostack.cveil.core.tenant.consent.invalidation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubscriptionManager}.
 *
 * <p>Uses a simple in-memory {@link InvalidationSubscriber} stub — no Redis.
 */
@DisplayName("SubscriptionManager")
class SubscriptionManagerTest {

    private StubSubscriber stubSubscriber;
    private SubscriptionManager manager;

    @BeforeEach
    void setUp() {
        stubSubscriber = new StubSubscriber();
        manager = new SubscriptionManager(stubSubscriber);
    }

    @Test
    @DisplayName("add() creates a subscription and tracks it")
    void addCreatesSubscription() {
        InvalidationSubscriber.Subscription sub = manager.add("conn-1", "tenant-a", msg -> {});
        assertNotNull(sub);
        assertEquals("tenant-a", sub.tenantId());
        assertEquals(1, manager.activeSubscriptionCount());
        assertEquals(1, manager.connectionCount());
    }

    @Test
    @DisplayName("Multiple subscriptions for same connection are tracked together")
    void multipleSubsSameConnection() {
        manager.add("conn-1", "tenant-a", msg -> {});
        manager.add("conn-1", "tenant-b", msg -> {});

        assertEquals(2, manager.activeSubscriptionCount());
        assertEquals(1, manager.connectionCount());
    }

    @Test
    @DisplayName("removeAll() cancels all subscriptions for a connection")
    void removeAllCancelsAll() {
        manager.add("conn-1", "tenant-a", msg -> {});
        manager.add("conn-1", "tenant-b", msg -> {});
        manager.add("conn-2", "tenant-a", msg -> {});

        int removed = manager.removeAll("conn-1");
        assertEquals(2, removed);
        assertEquals(1, manager.activeSubscriptionCount());
        assertEquals(1, manager.connectionCount());
    }

    @Test
    @DisplayName("removeAll() on unknown connection returns 0")
    void removeAllUnknown() {
        assertEquals(0, manager.removeAll("nonexistent"));
    }

    @Test
    @DisplayName("remove() cancels a single subscription")
    void removeSingle() {
        InvalidationSubscriber.Subscription sub1 = manager.add("conn-1", "tenant-a", msg -> {});
        manager.add("conn-1", "tenant-b", msg -> {});

        manager.remove("conn-1", sub1);
        assertEquals(1, manager.activeSubscriptionCount());
        assertEquals(1, manager.connectionCount());
    }

    @Test
    @DisplayName("remove() last subscription cleans up connection entry")
    void removeLastCleansUp() {
        InvalidationSubscriber.Subscription sub = manager.add("conn-1", "tenant-a", msg -> {});

        manager.remove("conn-1", sub);
        assertEquals(0, manager.activeSubscriptionCount());
        assertEquals(0, manager.connectionCount());
    }

    @Test
    @DisplayName("Metered sink tracks delivered messages")
    void metricsDelivered() throws Exception {
        AtomicReference<InvalidationSink> capturedSink = new AtomicReference<>();
        stubSubscriber.onSubscribe =
                (tenantId, sink) -> {
                    capturedSink.set(sink);
                };

        manager.add("conn-1", "tenant-a", msg -> {});

        // Simulate message delivery through the metered wrapper
        CacheInvalidationMessage msg =
                new CacheInvalidationMessage(
                        "id", "tenant-a", "s", "E", "f", null, "p", InvalidationAction.GRANTED);
        capturedSink.get().send(msg);

        assertEquals(1, manager.messagesDelivered());
        assertEquals(0, manager.errorCount());
    }

    @Test
    @DisplayName("Metered sink tracks errors")
    void metricsErrors() {
        AtomicReference<InvalidationSink> capturedSink = new AtomicReference<>();
        stubSubscriber.onSubscribe =
                (tenantId, sink) -> {
                    capturedSink.set(sink);
                };

        manager.add(
                "conn-1",
                "tenant-a",
                msg -> {
                    throw new RuntimeException("fail");
                });

        CacheInvalidationMessage msg =
                new CacheInvalidationMessage(
                        "id", "tenant-a", "s", "E", "f", null, "p", InvalidationAction.GRANTED);

        assertThrows(RuntimeException.class, () -> capturedSink.get().send(msg));
        assertEquals(0, manager.messagesDelivered());
        assertEquals(1, manager.errorCount());
    }

    @Test
    @DisplayName("close() cleans up all connections and closes subscriber")
    void closeCleanup() throws Exception {
        manager.add("conn-1", "tenant-a", msg -> {});
        manager.add("conn-2", "tenant-b", msg -> {});

        manager.close();

        assertEquals(0, manager.activeSubscriptionCount());
        assertEquals(0, manager.connectionCount());
        assertTrue(stubSubscriber.closed);
    }

    @Test
    @DisplayName("close() with concurrent add does not skip connections")
    void closeConcurrentWithAdd() throws Exception {
        int adderCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch addersReady = new CountDownLatch(adderCount);
        CopyOnWriteArrayList<Exception> errors = new CopyOnWriteArrayList<>();

        // Spawn threads that add connections concurrently
        for (int i = 0; i < adderCount; i++) {
            final String connId = "conn-" + i;
            Thread.ofPlatform()
                    .start(
                            () -> {
                                addersReady.countDown();
                                try {
                                    startLatch.await(5, TimeUnit.SECONDS);
                                    manager.add(connId, "tenant-a", msg -> {});
                                } catch (Exception e) {
                                    errors.add(e);
                                }
                            });
        }

        addersReady.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        // Brief pause to let some adds complete
        Thread.sleep(10);

        // Close while adds may still be in flight
        manager.close();

        assertTrue(errors.isEmpty(), "No exceptions expected");
        assertTrue(stubSubscriber.closed, "Subscriber should be closed");
    }

    // ── Stub subscriber ─────────────────────────────────────────────────────

    private static class StubSubscriber implements InvalidationSubscriber {
        boolean closed = false;
        java.util.function.BiConsumer<String, InvalidationSink> onSubscribe;
        private final AtomicInteger subCounter = new AtomicInteger();

        @Override
        public Subscription subscribe(String tenantId, InvalidationSink sink) {
            if (onSubscribe != null) onSubscribe.accept(tenantId, sink);

            return new Subscription() {
                @Override
                public String tenantId() {
                    return tenantId;
                }

                @Override
                public void cancel() {
                    /* no-op for stub */
                }
            };
        }

        @Override
        public void unsubscribe(Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
