package com.koostack.operio.plugin.redis.common.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.data.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.data.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.data.consent.invalidation.InvalidationSubscriber;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link RedisInvalidationSubscriber} reconnection using
 * Testcontainers + Toxiproxy.
 *
 * <p>Verifies that when the network link between subscriber and Redis is cut
 * and restored, the subscriber's exponential-backoff reconnection logic
 * re-subscribes all active channels and resumes message delivery.
 *
 * <p>Requires Docker.  Skipped automatically by Testcontainers if Docker is
 * unavailable.
 */
@DisplayName("RedisInvalidationSubscriber — Reconnection (Toxiproxy)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RedisInvalidationSubscriberReconnectIT {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static Network network;
    private static GenericContainer<?> redis;
    private static ToxiproxyContainer toxiproxy;

    private ToxiproxyContainer.ContainerProxy proxy;
    private JedisPooled subscriberJedis;  // routed through Toxiproxy
    private JedisPooled publisherJedis;   // direct to Redis (unaffected by proxy)
    private RedisInvalidationSubscriber subscriber;
    private RedisInvalidationPublisher publisher;

    @BeforeAll
    static void startContainers() {
        network = Network.newNetwork();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withNetwork(network)
                .withNetworkAliases("redis")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

        toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
                .withNetwork(network);

        redis.start();
        toxiproxy.start();
    }

    @AfterAll
    static void stopContainers() {
        if (toxiproxy != null) toxiproxy.stop();
        if (redis != null) redis.stop();
        if (network != null) network.close();
    }

    @BeforeEach
    void setUp() {
        proxy = toxiproxy.getProxy(redis, 6379);

        // Subscriber connects through the Toxiproxy (so we can cut it)
        subscriberJedis = new JedisPooled(toxiproxy.getHost(), proxy.getProxyPort());

        // Publisher connects directly to Redis (always available)
        publisherJedis = new JedisPooled(redis.getHost(), redis.getMappedPort(6379));

        publisher = new RedisInvalidationPublisher(publisherJedis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (subscriber != null) subscriber.close();
        // Ensure proxy is enabled for next test
        try { proxy.setConnectionCut(false); } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("Subscriber reconnects after network cut and resumes message delivery")
    void reconnectsAfterNetworkCut() throws Exception {
        BlockingQueue<CacheInvalidationMessage> received = new LinkedBlockingQueue<>();
        subscriber = new RedisInvalidationSubscriber(subscriberJedis, objectMapper);

        InvalidationSubscriber.Subscription sub = subscriber.subscribe("acme", received::put);
        assertEquals("acme", sub.tenantId());

        // Allow subscribe to propagate
        Thread.sleep(500);

        // ── Phase 1: verify normal delivery ──
        publisher.publish(msg("before-cut", "acme", InvalidationAction.GRANTED));
        CacheInvalidationMessage first = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(first, "Should receive message before network cut");
        assertEquals("before-cut", first.messageId());

        // ── Phase 2: cut the connection ──
        proxy.setConnectionCut(true);

        // Wait long enough for the subscriber thread to detect the disconnect.
        // JedisPubSub's blocking read will fail almost immediately when the
        // TCP connection is reset.
        Thread.sleep(2_000);

        // ── Phase 3: restore the connection ──
        proxy.setConnectionCut(false);

        // The subscriber's reconnection backoff starts at 1s.  Allow enough
        // time for one or two backoff iterations plus the re-subscribe.
        Thread.sleep(5_000);

        // ── Phase 4: verify delivery resumes ──
        publisher.publish(msg("after-reconnect", "acme", InvalidationAction.REVOKED));
        CacheInvalidationMessage second = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(second, "Should receive message after reconnection");
        assertEquals("after-reconnect", second.messageId());
        assertEquals(InvalidationAction.REVOKED, second.action());
    }

    @Test
    @DisplayName("Multiple channels are re-subscribed after reconnection")
    void multipleChannelsResubscribed() throws Exception {
        BlockingQueue<CacheInvalidationMessage> sinkA = new LinkedBlockingQueue<>();
        BlockingQueue<CacheInvalidationMessage> sinkB = new LinkedBlockingQueue<>();

        subscriber = new RedisInvalidationSubscriber(subscriberJedis, objectMapper);
        subscriber.subscribe("tenant-a", sinkA::put);
        subscriber.subscribe("tenant-b", sinkB::put);
        Thread.sleep(500);

        // Verify both work before disruption
        publisher.publish(msg("a1", "tenant-a", InvalidationAction.GRANTED));
        publisher.publish(msg("b1", "tenant-b", InvalidationAction.REVOKED));
        assertNotNull(sinkA.poll(5, TimeUnit.SECONDS), "Tenant A should receive before cut");
        assertNotNull(sinkB.poll(5, TimeUnit.SECONDS), "Tenant B should receive before cut");

        // Cut and restore
        proxy.setConnectionCut(true);
        Thread.sleep(2_000);
        proxy.setConnectionCut(false);
        Thread.sleep(5_000);

        // Both channels should work after reconnect
        publisher.publish(msg("a2", "tenant-a", InvalidationAction.GRANTED));
        publisher.publish(msg("b2", "tenant-b", InvalidationAction.REVOKED));
        assertNotNull(sinkA.poll(10, TimeUnit.SECONDS), "Tenant A should receive after reconnect");
        assertNotNull(sinkB.poll(10, TimeUnit.SECONDS), "Tenant B should receive after reconnect");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static CacheInvalidationMessage msg(String id, String tenantId, InvalidationAction action) {
        return new CacheInvalidationMessage(
                id, tenantId, "subject", "Entity", "field", null, "purpose", action);
    }
}
