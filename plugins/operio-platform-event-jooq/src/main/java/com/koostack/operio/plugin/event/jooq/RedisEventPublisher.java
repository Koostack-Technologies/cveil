package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.Map;

/**
 * Publishes platform events to a Redis Stream for real-time fanout.
 *
 * <p>Each event is appended to a Redis Stream keyed by
 * {@code {appName}:platform-events}. Consumers (e.g.
 * {@link com.koostack.operio.core.event.PlatformEventListener}
 * implementations) can subscribe via Redis consumer groups for
 * reliable, at-least-once delivery.
 */
public class RedisEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);

    private final JedisProvider jedisProvider;
    private final String streamKey;

    /**
     * @param jedisProvider provider for the Redis client
     * @param appName       application name used as key prefix
     */
    public RedisEventPublisher(JedisProvider jedisProvider, String appName) {
        this.jedisProvider = jedisProvider;
        this.streamKey = appName + ":platform-events";
    }

    /**
     * Publishes pre-built fields to the Redis Stream.  Used by the outbox
     * poller which reads serialised rows directly from the database.
     */
    public void publishRaw(Map<String, String> fields) {
        try {
            JedisPooled jedis = jedisProvider.get();
            jedis.xadd(streamKey, fields, new redis.clients.jedis.params.XAddParams());
            log.debug("Published event {} to Redis stream {}", fields.get(PlatformEvent.FIELD_EVENT_ID), streamKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish event to Redis stream", e);
        }
    }
}
