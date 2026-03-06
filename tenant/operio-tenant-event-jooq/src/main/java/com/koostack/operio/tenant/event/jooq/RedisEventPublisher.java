package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Publishes tenant events to per-tenant Redis Streams for real-time fanout.
 *
 * <p>Each event is routed to a dedicated stream key ({@code operio:stream:tenant:{tenantId}}) based
 * on the tenant ID extracted from the event fields.
 */
public class RedisEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);

    /** Prefix for per-tenant stream keys. */
    public static final String STREAM_KEY_PREFIX = "operio:stream:tenant:";

    private final JedisProvider jedisProvider;

    public RedisEventPublisher(JedisProvider jedisProvider) {
        this.jedisProvider = jedisProvider;
    }

    /**
     * Publishes pre-built fields to the tenant's dedicated Redis Stream.
     *
     * <p>Uses approximate trimming ({@code MAXLEN ~ 10000}) to prevent unbounded stream growth.
     * Events are durably stored in Postgres — the stream is a transient transport layer.
     */
    public void publishRaw(Map<String, String> fields) {
        String tenantId = fields.get(TenantEvent.FIELD_TENANT_ID);
        String streamKey = streamKeyForTenant(tenantId);
        try {
            JedisPooled jedis = jedisProvider.get();
            var params =
                    new redis.clients.jedis.params.XAddParams().approximateTrimming().maxLen(10000);
            jedis.xadd(streamKey, params, fields);
            log.debug(
                    "Published event {} to Redis stream {}",
                    fields.get(TenantEvent.FIELD_EVENT_ID),
                    streamKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish event to Redis stream " + streamKey, e);
        }
    }

    /**
     * Returns the per-tenant stream key for the given tenant ID.
     *
     * @param tenantId the tenant identifier
     * @return the stream key ({@code operio:stream:tenant:{tenantId}})
     * @throws IllegalArgumentException if tenantId is null
     */
    public static String streamKeyForTenant(String tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return STREAM_KEY_PREFIX + tenantId;
    }
}
