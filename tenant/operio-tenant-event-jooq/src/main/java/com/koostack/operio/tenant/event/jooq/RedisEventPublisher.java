package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Publishes tenant events to sharded Redis Streams for real-time fanout.
 *
 * <p>Each event is routed to one of {@value #SHARD_COUNT} stream keys
 * ({@code operio:stream:domain:0} … {@code operio:stream:domain:15})
 * based on a deterministic hash of the tenant ID extracted from the
 * event fields.
 */
public class RedisEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);

    public static final int SHARD_COUNT = 16;
    private static final String STREAM_KEY_PREFIX = "operio:stream:domain:";

    private final JedisProvider jedisProvider;

    public RedisEventPublisher(JedisProvider jedisProvider) {
        this.jedisProvider = jedisProvider;
    }

    /**
     * Publishes pre-built fields to the appropriate sharded Redis Stream,
     * determined by the {@code tenantId} field in the map.
     */
    public void publishRaw(Map<String, String> fields) {
        String tenantId = fields.get(TenantEvent.FIELD_TENANT_ID);
        String streamKey = streamKeyForTenant(tenantId);
        try {
            JedisPooled jedis = jedisProvider.get();
            jedis.xadd(streamKey, fields, new redis.clients.jedis.params.XAddParams());
            log.debug("Published event {} to Redis stream {}", fields.get(TenantEvent.FIELD_EVENT_ID), streamKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish event to Redis stream " + streamKey, e);
        }
    }

    /**
     * Deterministically maps a tenant ID to a sharded stream key.
     *
     * @throws IllegalArgumentException if tenantId is null
     */
    public static String streamKeyForTenant(String tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null for shard routing");
        }
        int hash = tenantId.hashCode();
        int partitionIndex = (hash & 0x7FFFFFFF) % SHARD_COUNT;
        return STREAM_KEY_PREFIX + partitionIndex;
    }

    /**
     * Returns an unmodifiable list of all sharded stream keys.
     */
    public static List<String> allStreamKeys() {
        List<String> keys = new ArrayList<>(SHARD_COUNT);
        for (int i = 0; i < SHARD_COUNT; i++) {
            keys.add(STREAM_KEY_PREFIX + i);
        }
        return Collections.unmodifiableList(keys);
    }
}
