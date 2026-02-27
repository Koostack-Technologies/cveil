package com.koostack.operio.plugin.redis.common.pubsub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.data.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.data.consent.invalidation.InvalidationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes {@link CacheInvalidationMessage}s to Redis Pub/Sub channels.
 *
 * <p>Channel name format: {@code operio:consent-cache-invalidation:{tenantId}}.
 *
 * <p><b>Important:</b> The {@code adminJedis} connection must be an admin pool
 * (not a tenant-scoped pool from {@code TenantAwareJedisProvider}).  Tenant ACL
 * users lack pub/sub channel permissions and {@code PUBLISH} will fail with
 * {@code NOPERM}.
 *
 * <p>Fire-and-forget semantics: publish failures are logged at WARN but never
 * propagated.  The invalidation pipeline is at-most-once by design.
 */
public class RedisInvalidationPublisher implements InvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisInvalidationPublisher.class);
    static final String CHANNEL_PREFIX = "operio:consent-cache-invalidation:";

    private final JedisPooled adminJedis;
    private final ObjectMapper objectMapper;

    public RedisInvalidationPublisher(JedisPooled adminJedis, ObjectMapper objectMapper) {
        this.adminJedis = Objects.requireNonNull(adminJedis, "adminJedis");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void publish(CacheInvalidationMessage message) {
        String channel = channelFor(message.tenantId());
        try {
            String json = objectMapper.writeValueAsString(message);
            adminJedis.publish(channel, json);
            log.debug("Published invalidation to {} — action={}, messageId={}",
                channel, message.action(), message.messageId());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize invalidation message {}", message.messageId(), e);
        } catch (Exception e) {
            log.warn("Failed to publish invalidation to {} — messageId={}",
                channel, message.messageId(), e);
        }
    }

    @Override
    public void publishBatch(List<CacheInvalidationMessage> messages) {
        if (messages == null || messages.isEmpty()) return;

        // Group by tenant channel for pipelining efficiency
        Map<String, List<String>> channelPayloads = new LinkedHashMap<>();
        for (CacheInvalidationMessage msg : messages) {
            String channel = channelFor(msg.tenantId());
            try {
                String json = objectMapper.writeValueAsString(msg);
                channelPayloads.computeIfAbsent(channel, k -> new ArrayList<>()).add(json);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize invalidation message {}", msg.messageId(), e);
            }
        }

        try (Pipeline pipeline = adminJedis.pipelined()) {
            for (var entry : channelPayloads.entrySet()) {
                for (String json : entry.getValue()) {
                    pipeline.publish(entry.getKey(), json);
                }
            }
            pipeline.sync();
            log.debug("Batch-published {} invalidation messages across {} channels",
                messages.size(), channelPayloads.size());
        } catch (Exception e) {
            log.warn("Failed to batch-publish invalidation messages", e);
        }
    }

    static String channelFor(String tenantId) {
        return CHANNEL_PREFIX + tenantId;
    }
}
