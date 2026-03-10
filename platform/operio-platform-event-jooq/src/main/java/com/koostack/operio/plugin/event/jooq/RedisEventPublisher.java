package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.plugin.redis.common.JedisProvider;
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
            Pipeline pipeline = jedis.pipelined();
            for (Map<String, String> fields : batch) {
                pipeline.xadd(streamKey, fields, new redis.clients.jedis.params.XAddParams());
            }
            pipeline.sync();
            log.debug("Pipelined {} events to Redis stream {}", batch.size(), streamKey);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to pipeline " + batch.size() + " events to Redis stream " + streamKey,
                    e);
        }
    }
}
