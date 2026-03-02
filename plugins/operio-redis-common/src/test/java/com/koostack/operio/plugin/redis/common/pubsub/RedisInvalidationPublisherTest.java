package com.koostack.operio.plugin.redis.common.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RedisInvalidationPublisher}.
 *
 * <p>Uses jedis-mock (in-process Redis) so no Docker / Testcontainers required.
 */
@DisplayName("RedisInvalidationPublisher")
class RedisInvalidationPublisherTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("channelFor() produces correct channel name")
    void channelFormat() {
        JedisPooled dummy = new JedisPooled("localhost", 1);
        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(dummy, objectMapper);
        assertEquals("operio:consent-cache-invalidation:acme", publisher.channelFor("acme"));
        assertEquals("operio:consent-cache-invalidation:tenant-123", publisher.channelFor("tenant-123"));
    }

    @Test
    @DisplayName("publish() does not throw on serialization of a valid message (no live Redis)")
    void publishDoesNotThrowWhenRedisUnavailable() {
        // Use a dummy connection that will fail at publish time — publisher should catch and log
        JedisPooled dummy = new JedisPooled("localhost", 1);
        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(dummy, objectMapper);

        CacheInvalidationMessage msg = new CacheInvalidationMessage(
            "id-1", "tenant-a", "subject", "Entity", "field",
            null, "purpose", InvalidationAction.GRANTED
        );

        // Should not throw — fire-and-forget semantics
        assertDoesNotThrow(() -> publisher.publish(msg));
    }

    @Test
    @DisplayName("publishBatch() handles null and empty lists gracefully")
    void publishBatchEdgeCases() {
        JedisPooled dummy = new JedisPooled("localhost", 1);
        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(dummy, objectMapper);

        assertDoesNotThrow(() -> publisher.publishBatch(null));
        assertDoesNotThrow(() -> publisher.publishBatch(List.of()));
    }

    @Test
    @DisplayName("publishBatch() does not throw when Redis is unavailable")
    void publishBatchFireAndForget() {
        JedisPooled dummy = new JedisPooled("localhost", 1);
        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(dummy, objectMapper);

        List<CacheInvalidationMessage> batch = List.of(
            new CacheInvalidationMessage("id-1", "t-a", "s1", "E", "f1", null, "p", InvalidationAction.GRANTED),
            new CacheInvalidationMessage("id-2", "t-a", "s2", "E", "f2", null, "p", InvalidationAction.REVOKED),
            new CacheInvalidationMessage("id-3", "t-b", null, null, null, "cat", null, InvalidationAction.CATEGORY_CHANGED)
        );

        assertDoesNotThrow(() -> publisher.publishBatch(batch));
    }

    @Test
    @DisplayName("Constructor rejects null adminJedis with NullPointerException")
    void constructorRejectsNullJedis() {
        assertThrows(NullPointerException.class,
                () -> new RedisInvalidationPublisher(null, objectMapper));
    }

    @Test
    @DisplayName("Constructor rejects null objectMapper with NullPointerException")
    void constructorRejectsNullObjectMapper() {
        JedisPooled dummy = new JedisPooled("localhost", 1);
        assertThrows(NullPointerException.class,
                () -> new RedisInvalidationPublisher(dummy, null));
    }

    @Test
    @DisplayName("DEFAULT_CHANNEL_PREFIX is shared with subscriber (cross-check)")
    void channelPrefixSharedWithSubscriber() {
        assertEquals(
                RedisInvalidationPublisher.DEFAULT_CHANNEL_PREFIX,
                RedisInvalidationSubscriber.DEFAULT_CHANNEL_PREFIX,
                "Publisher and subscriber must use the same default channel prefix");
    }

    @Test
    @DisplayName("Custom channel prefix is used in channelFor()")
    void customChannelPrefix() {
        JedisPooled dummy = new JedisPooled("localhost", 1);
        RedisInvalidationPublisher publisher = new RedisInvalidationPublisher(dummy, objectMapper, "operio:admin:");
        assertEquals("operio:admin:acme", publisher.channelFor("acme"));
        assertEquals("operio:admin:", publisher.channelPrefix());
    }
}
