package com.koostack.cveil.plugin.event.jooq;

import com.koostack.cveil.core.event.models.PlatformEvent;
import com.koostack.cveil.plugin.redis.common.JedisProvider;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

/**
 * Publishes events to Redis Streams for real-time fanout.
 *
 * <p>Routes events to the correct stream based on tenant:
 *
 * <ul>
 *   <li>Platform events → {@code {appName}:platform-events}
 *   <li>Tenant events → {@code {appName}:stream:shard:{N}} where N = hash(tenantId) % shardCount
 * </ul>
 *
 * <p>Tenant events are distributed across a fixed number of shard streams (default 8) so that the
 * number of Redis connections is O(1) regardless of tenant count.
 */
public class RedisEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);
    public static final int DEFAULT_SHARD_COUNT = 16;

    private final JedisProvider jedisProvider;
    private final String appName;
    private final int shardCount;

    public RedisEventPublisher(JedisProvider jedisProvider, String appName) {
        this(jedisProvider, appName, DEFAULT_SHARD_COUNT);
    }

    public RedisEventPublisher(JedisProvider jedisProvider, String appName, int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1, got " + shardCount);
        }
        this.jedisProvider = jedisProvider;
        this.appName = appName;
        this.shardCount = shardCount;
    }

    /** Returns the number of shard streams. */
    public int shardCount() {
        return shardCount;
    }

    /** Returns the platform events stream key. */
    public String platformStreamKey() {
        return appName + ":platform-events";
    }

    /**
     * Returns the Redis stream key used as the outbox nudge channel. Writers XADD an entry per
     * event with the tenant ID; the {@link OutboxPoller} reads entries via {@code XREAD BLOCK} to
     * discover which tenants have new work without a DB discovery query.
     */
    public String outboxNudgeStreamKey() {
        return appName + ":outbox:nudge";
    }

    /**
     * Returns the shard stream key for the given tenant. Uses a deterministic hash so the same
     * tenant always routes to the same shard.
     */
    public String shardStreamKey(String tenantId) {
        int shard = Math.floorMod(tenantId.hashCode(), shardCount);
        return appName + ":stream:shard:" + shard;
    }

    /**
     * Returns the shard stream key for a given shard index.
     *
     * @param shard the shard index (0-based)
     */
    public String shardStreamKeyByIndex(int shard) {
        return appName + ":stream:shard:" + shard;
    }

    /**
     * Returns the tenant events stream key for the given tenant.
     *
     * @deprecated Use {@link #shardStreamKey(String)} instead. Per-tenant streams are replaced by
     *     shard streams.
     */
    @Deprecated(forRemoval = true)
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

    /**
     * Publishes a batch of events to the specified Redis Stream using a pipeline. All XADD commands
     * are buffered and sent in a single round-trip, eliminating per-event network overhead.
     */
    public void publishBatchPipelined(String streamKey, List<Map<String, String>> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            JedisPooled jedis = jedisProvider.get();
            try (Pipeline pipeline = jedis.pipelined()) {
                for (Map<String, String> fields : batch) {
                    pipeline.xadd(streamKey, fields, new redis.clients.jedis.params.XAddParams());
                }
                pipeline.sync();
            }
            log.debug("Pipelined {} events to Redis stream {}", batch.size(), streamKey);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to pipeline " + batch.size() + " events to Redis stream " + streamKey,
                    e);
        }
    }
}
