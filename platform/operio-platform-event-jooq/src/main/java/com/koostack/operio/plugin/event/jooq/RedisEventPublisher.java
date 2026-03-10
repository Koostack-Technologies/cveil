package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Publishes events to Redis Streams for real-time fanout.
 *
 * <p>Routes events to the correct stream based on tenant:
 *
 * <ul>
 *   <li>Platform events → {@code {appName}:platform-events}
 *   <li>Tenant events → {@code {appName}:stream:tenant:{tenantId}}
 * </ul>
 */
public class RedisEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);

    private final JedisProvider jedisProvider;
    private final String appName;

    public RedisEventPublisher(JedisProvider jedisProvider, String appName) {
        this.jedisProvider = jedisProvider;
        this.appName = appName;
    }

    /** Returns the platform events stream key. */
    public String platformStreamKey() {
        return appName + ":platform-events";
    }

    /** Returns the tenant events stream key for the given tenant. */
    public String tenantStreamKey(String tenantId) {
        return appName + ":stream:tenant:" + tenantId;
    }

    /** Publishes pre-built fields to the specified Redis Stream. */
    public void publishRaw(String streamKey, Map<String, String> fields) {
        try {
            JedisPooled jedis = jedisProvider.get();
            jedis.xadd(streamKey, fields, new redis.clients.jedis.params.XAddParams());
            log.debug(
                    "Published event {} to Redis stream {}",
                    fields.get(PlatformEvent.FIELD_EVENT_ID),
                    streamKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish event to Redis stream " + streamKey, e);
        }
    }

    /** Publishes to the default platform stream (backward compatibility). */
    public void publishRaw(Map<String, String> fields) {
        publishRaw(platformStreamKey(), fields);
    }
}
