package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.Map;

/**
 * Publishes tenant events to a Redis Stream for real-time fanout.
 */
public class RedisEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);

    private final JedisProvider jedisProvider;
    private final String streamKey;

    public RedisEventPublisher(JedisProvider jedisProvider, String appName) {
        this.jedisProvider = jedisProvider;
        this.streamKey = appName + ":tenant-events";
    }

    public void publishRaw(Map<String, String> fields) {
        try {
            JedisPooled jedis = jedisProvider.get();
            jedis.xadd(streamKey, fields, new redis.clients.jedis.params.XAddParams());
            log.debug("Published event {} to Redis stream {}", fields.get(TenantEvent.FIELD_EVENT_ID), streamKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish event to Redis stream", e);
        }
    }
}
