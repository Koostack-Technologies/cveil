package com.koostack.operio.plugin.redis.common.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fppt.jedismock.RedisServer;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationSink;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationSubscriber;
import org.junit.jupiter.api.*;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RedisInvalidationSubscriber} using jedis-mock.
 *
 * <p>Verifies the full pub/sub round-trip: publish → Redis → subscriber → sink.
 */
@DisplayName("RedisInvalidationSubscriber")
class RedisInvalidationSubscriberTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private RedisServer redisServer;
    private JedisPooled jedis;
    private RedisInvalidationSubscriber subscriber;

    @BeforeEach
    void setUp() throws Exception {
        redisServer = RedisServer.newRedisServer().start();
        jedis = new JedisPooled(redisServer.getHost(), redisServer.getBindPort());
        subscriber = new RedisInvalidationSubscriber(jedis, objectMapper);
        // Give the subscriber thread time to start and subscribe to control channel
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (subscriber != null) subscriber.close();
        if (redisServer != null) redisServer.stop();
    }

    @Test
    @DisplayName("channelFor() produces correct channel name")
    void channelFormat() {
        assertEquals("operio:consent-cache-invalidation:acme",
                RedisInvalidationSubscriber.channelFor("acme"));
    }

    @Test
    @DisplayName("CONTROL_CHANNEL uses __control__ sentinel")
    void controlChannel() {
        assertEquals("operio:consent-cache-invalidation:__control__",
                RedisInvalidationSubscriber.CONTROL_CHANNEL);
    }

    @Test
    @DisplayName("subscribe() returns a Subscription with correct tenantId")
    void subscribeReturnsTenantId() {
        InvalidationSink sink = msg -> {};
        InvalidationSubscriber.Subscription sub = subscriber.subscribe("tenant-x", sink);

        assertEquals("tenant-x", sub.tenantId());
        sub.cancel();
    }

    @Test
    @DisplayName("Full round-trip: publish → subscriber → sink receives message")
    void fullRoundTrip() throws Exception {
        BlockingQueue<CacheInvalidationMessage> received = new LinkedBlockingQueue<>();
        subscriber.subscribe("acme", received::put);

        // Give Redis time to process the SUBSCRIBE command
        Thread.sleep(300);

        // Publish via a separate publisher
        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(jedis, objectMapper);
        CacheInvalidationMessage msg = new CacheInvalidationMessage(
                "msg-1", "acme", "user-42", "User", "email",
                null, "marketing", InvalidationAction.GRANTED);

        publisher.publish(msg);

        CacheInvalidationMessage result = received.poll(3, TimeUnit.SECONDS);
        assertNotNull(result, "Sink should have received the message");
        assertEquals("msg-1", result.messageId());
        assertEquals("acme", result.tenantId());
        assertEquals("user-42", result.subject());
        assertEquals(InvalidationAction.GRANTED, result.action());
    }

    @Test
    @DisplayName("Multi-tenant isolation: tenant A messages don't leak to tenant B")
    void multiTenantIsolation() throws Exception {
        BlockingQueue<CacheInvalidationMessage> sinkA = new LinkedBlockingQueue<>();
        BlockingQueue<CacheInvalidationMessage> sinkB = new LinkedBlockingQueue<>();

        subscriber.subscribe("tenant-a", sinkA::put);
        subscriber.subscribe("tenant-b", sinkB::put);
        Thread.sleep(300);

        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(jedis, objectMapper);

        // Publish to tenant-a only
        publisher.publish(new CacheInvalidationMessage(
                "msg-a", "tenant-a", "s1", "E", "f", null, "p", InvalidationAction.GRANTED));

        CacheInvalidationMessage resultA = sinkA.poll(3, TimeUnit.SECONDS);
        assertNotNull(resultA, "Tenant A sink should receive the message");
        assertEquals("tenant-a", resultA.tenantId());

        // Tenant B should NOT receive it
        CacheInvalidationMessage resultB = sinkB.poll(500, TimeUnit.MILLISECONDS);
        assertNull(resultB, "Tenant B sink should NOT receive tenant A's message");
    }

    @Test
    @DisplayName("Multiple sinks on same channel both receive messages")
    void multipleSinksFanout() throws Exception {
        BlockingQueue<CacheInvalidationMessage> sink1 = new LinkedBlockingQueue<>();
        BlockingQueue<CacheInvalidationMessage> sink2 = new LinkedBlockingQueue<>();

        subscriber.subscribe("acme", sink1::put);
        subscriber.subscribe("acme", sink2::put);
        Thread.sleep(300);

        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(jedis, objectMapper);
        publisher.publish(new CacheInvalidationMessage(
                "msg-1", "acme", "s1", "E", "f", null, "p", InvalidationAction.REVOKED));

        assertNotNull(sink1.poll(3, TimeUnit.SECONDS), "Sink 1 should receive");
        assertNotNull(sink2.poll(3, TimeUnit.SECONDS), "Sink 2 should receive");
    }

    @Test
    @DisplayName("cancel() stops delivery to the cancelled sink")
    void cancelStopsDelivery() throws Exception {
        BlockingQueue<CacheInvalidationMessage> sink = new LinkedBlockingQueue<>();
        InvalidationSubscriber.Subscription sub = subscriber.subscribe("acme", sink::put);
        Thread.sleep(300);

        sub.cancel();
        Thread.sleep(100);

        // Publish after cancel — sink should NOT receive
        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(jedis, objectMapper);
        publisher.publish(new CacheInvalidationMessage(
                "msg-1", "acme", "s1", "E", "f", null, "p", InvalidationAction.GRANTED));

        CacheInvalidationMessage result = sink.poll(500, TimeUnit.MILLISECONDS);
        assertNull(result, "Cancelled subscription should not receive messages");
    }

    @Test
    @DisplayName("unsubscribe(subscription) is equivalent to subscription.cancel()")
    void unsubscribeEquivalentToCancel() throws Exception {
        BlockingQueue<CacheInvalidationMessage> sink = new LinkedBlockingQueue<>();
        InvalidationSubscriber.Subscription sub = subscriber.subscribe("acme", sink::put);
        Thread.sleep(300);

        subscriber.unsubscribe(sub);
        Thread.sleep(100);

        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(jedis, objectMapper);
        publisher.publish(new CacheInvalidationMessage(
                "msg-1", "acme", "s1", "E", "f", null, "p", InvalidationAction.GRANTED));

        assertNull(sink.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("close() shuts down cleanly and rejects new subscriptions")
    void closeRejectsNewSubscriptions() throws Exception {
        subscriber.close();
        Thread.sleep(200);

        assertThrows(IllegalStateException.class,
                () -> subscriber.subscribe("acme", msg -> {}));
    }

    @Test
    @DisplayName("Failing sink does not affect other sinks on the same channel")
    void failingSinkIsolation() throws Exception {
        BlockingQueue<CacheInvalidationMessage> goodSink = new LinkedBlockingQueue<>();
        InvalidationSink badSink = msg -> { throw new RuntimeException("boom"); };

        subscriber.subscribe("acme", badSink);
        subscriber.subscribe("acme", goodSink::put);
        Thread.sleep(300);

        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(jedis, objectMapper);
        publisher.publish(new CacheInvalidationMessage(
                "msg-1", "acme", "s1", "E", "f", null, "p", InvalidationAction.GRANTED));

        CacheInvalidationMessage result = goodSink.poll(3, TimeUnit.SECONDS);
        assertNotNull(result, "Good sink should still receive messages despite bad sink");
    }

    @Test
    @DisplayName("Batch publish delivers all messages to subscriber")
    void batchPublishRoundTrip() throws Exception {
        BlockingQueue<CacheInvalidationMessage> sink = new LinkedBlockingQueue<>();
        subscriber.subscribe("acme", sink::put);
        Thread.sleep(300);

        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(jedis, objectMapper);
        publisher.publishBatch(List.of(
                new CacheInvalidationMessage("m1", "acme", "s1", "E", "f1", null, "p", InvalidationAction.GRANTED),
                new CacheInvalidationMessage("m2", "acme", "s2", "E", "f2", null, "p", InvalidationAction.REVOKED),
                new CacheInvalidationMessage("m3", "acme", null, null, null, null, null, InvalidationAction.ALL_REVOKED)
        ));

        // Collect all 3
        List<CacheInvalidationMessage> results = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            CacheInvalidationMessage m = sink.poll(3, TimeUnit.SECONDS);
            assertNotNull(m, "Should receive message " + (i + 1));
            results.add(m);
        }
        assertEquals(3, results.size());
    }

    @Test
    @DisplayName("Concurrent subscribe() calls do not cause ArrayIndexOutOfBoundsException")
    void concurrentSubscribeStressTest() throws Exception {
        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<Exception> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String tenantId = "stress-tenant-" + i;
            Thread.ofPlatform().start(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    subscriber.subscribe(tenantId, msg -> {});
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads simultaneously
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should finish");
        assertTrue(errors.isEmpty(),
                "No exceptions expected, but got: " + errors);
    }
}
