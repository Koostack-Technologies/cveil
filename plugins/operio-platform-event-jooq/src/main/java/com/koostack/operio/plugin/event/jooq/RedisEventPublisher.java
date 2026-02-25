package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.HashMap;
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
     * Publishes an event to the Redis Stream.
     *
     * @param event             the platform event
     * @param eventId           the database-assigned event id
     * @param serializedPayload the serialized payload string
     */
    public <T> void publish(PlatformEvent<T> event, long eventId, String serializedPayload) {
        try {
            JedisPooled jedis = jedisProvider.get();
            Map<String, String> fields = new HashMap<>();
            fields.put("eventId", String.valueOf(eventId));
            fields.put("tenantId", event.tenantId() != null ? event.tenantId().toString() : "");
            fields.put("aggregateId", event.aggregateId() != null ? event.aggregateId().toString() : "");
            fields.put("aggregateType", event.aggregateType());
            fields.put("eventType", event.eventType());
            fields.put("version", String.valueOf(event.version()));
            if (serializedPayload != null) {
                fields.put("payload", serializedPayload);
            }
            jedis.xadd(streamKey, fields, new redis.clients.jedis.params.XAddParams());
            log.debug("Published event {} to Redis stream {}", eventId, streamKey);
        } catch (Exception e) {
            log.warn("Failed to publish event {} to Redis stream — event is persisted in DB", eventId, e);
        }
    }
}
