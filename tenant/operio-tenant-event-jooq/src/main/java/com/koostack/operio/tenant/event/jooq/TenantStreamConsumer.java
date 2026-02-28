package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Per-tenant stream consumer that spawns one virtual thread per tenant
 * per registered handler.
 *
 * <p>Replaces {@code ShardedStreamConsumer} — instead of 16 fixed shards
 * multiplexing all tenants, each tenant gets its own Redis Stream
 * ({@code operio:stream:tenant:{tenantId}}) with independent consumer
 * groups for each projection handler.
 *
 * <h2>Thread Model</h2>
 * <p>Uses {@link Executors#newVirtualThreadPerTaskExecutor()} — each
 * tenant×handler pair gets a virtual thread that blocks on
 * {@code XREADGROUP}. Virtual threads are cheap (~1KB stack) and unmount
 * from carrier threads during the blocking read.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #registerHandler} — register projection handlers before
 *       adding tenants</li>
 *   <li>{@link #addTenant} — idempotent; creates consumer groups and
 *       spawns reader threads</li>
 *   <li>{@link #removeTenant} — cancels reader threads for one tenant</li>
 *   <li>{@link #close} — graceful shutdown of all consumers</li>
 * </ul>
 *
 * <h2>Poison-Message Safety</h2>
 * <p>If a handler throws, the message is still {@code XACK}'d to prevent
 * it from blocking the stream. The exception is logged at WARN level.
 *
 * @see StreamEventHandler
 * @see RedisEventPublisher#streamKeyForTenant(String)
 */
public class TenantStreamConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TenantStreamConsumer.class);

    /**
     * A registered handler with its consumer group name.
     */
    record HandlerRegistration(String groupName, StreamEventHandler handler) {}

    /** Minimum backoff on Redis errors (milliseconds). */
    private static final long MIN_BACKOFF_MS = 1_000;
    /** Maximum backoff on Redis errors (milliseconds). */
    private static final long MAX_BACKOFF_MS = 30_000;

    private final JedisProvider jedisProvider;
    private final List<HandlerRegistration> handlers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, List<Future<?>>> tenantConsumers = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final int blockMs;
    private final int batchSize;
    /** Unique per JVM instance — avoids consumer name collisions in multi-pod deployments. */
    private final String instanceId = System.getenv().getOrDefault("HOSTNAME", "operio")
        + "-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean running = true;

    /**
     * @param jedisProvider Redis connection provider (shared/admin pool)
     * @param blockMs       XREADGROUP block timeout in milliseconds (must fit in int)
     * @param batchSize     max entries per XREADGROUP call
     * @throws IllegalArgumentException if blockMs is negative or exceeds Integer.MAX_VALUE
     */
    public TenantStreamConsumer(JedisProvider jedisProvider, long blockMs, int batchSize) {
        if (blockMs < 0 || blockMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "blockMs must be between 0 and " + Integer.MAX_VALUE + ", got " + blockMs);
        }
        this.jedisProvider = jedisProvider;
        this.blockMs = (int) blockMs;
        this.batchSize = batchSize;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Registers a handler with its consumer group name. Must be called
     * before any tenants are added.
     *
     * @param groupName the Redis consumer group name
     * @param handler   the event handler
     */
    public void registerHandler(String groupName, StreamEventHandler handler) {
        handlers.add(new HandlerRegistration(groupName, handler));
    }

    /**
     * Adds a tenant — creates consumer groups on the tenant's stream and
     * spawns one virtual thread per registered handler. Idempotent: calling
     * twice for the same tenant is a no-op.
     *
     * @param tenantId the tenant identifier
     * @throws IllegalStateException if the consumer has been closed
     */
    public void addTenant(String tenantId) {
        if (!running) {
            throw new IllegalStateException("Cannot add tenant — consumer is shut down");
        }
        tenantConsumers.computeIfAbsent(tenantId, id -> {
            String streamKey = RedisEventPublisher.streamKeyForTenant(id);
            List<Future<?>> futures = new ArrayList<>();

            for (HandlerRegistration reg : handlers) {
                ensureConsumerGroup(streamKey, reg.groupName());
                Future<?> future = executor.submit(() -> consumeLoop(id, streamKey, reg));
                futures.add(future);
            }

            log.info("Started {} consumer(s) for tenant '{}' on stream '{}'",
                futures.size(), id, streamKey);
            return futures;
        });
    }

    /**
     * Removes a tenant — cancels all reader threads for the tenant.
     *
     * @param tenantId the tenant identifier
     */
    public void removeTenant(String tenantId) {
        List<Future<?>> futures = tenantConsumers.remove(tenantId);
        if (futures == null) {
            log.debug("Tenant '{}' has no active consumers — skipping removeTenant", tenantId);
            return;
        }
        for (Future<?> future : futures) {
            future.cancel(true);
        }
        log.info("Stopped {} consumer(s) for tenant '{}'", futures.size(), tenantId);
    }

    /**
     * Returns the set of tenant IDs with active consumers.
     */
    public java.util.Set<String> activeTenants() {
        return java.util.Collections.unmodifiableSet(tenantConsumers.keySet());
    }

    /**
     * Returns {@code true} if the consumer is running and all tenant
     * consumer threads are alive (not cancelled or completed).
     *
     * <p>Use this in liveness/readiness probes to detect silently-dead
     * consumer threads.
     */
    public boolean isHealthy() {
        if (!running) return false;
        for (var entry : tenantConsumers.entrySet()) {
            for (Future<?> f : entry.getValue()) {
                if (f.isDone()) {
                    log.warn("Consumer thread for tenant '{}' has died unexpectedly", entry.getKey());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns pending entry counts per consumer group for a tenant's stream.
     *
     * @param tenantId the tenant identifier
     * @return map of group name → pending count
     */
    public Map<String, Long> getPendingCounts(String tenantId) {
        String streamKey = RedisEventPublisher.streamKeyForTenant(tenantId);
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        JedisPooled jedis = jedisProvider.get();
        for (HandlerRegistration reg : handlers) {
            try {
                var pending = jedis.xpending(streamKey, reg.groupName());
                counts.put(reg.groupName(), pending.getTotal());
            } catch (Exception e) {
                log.debug("Failed to get pending count for group '{}' on stream '{}': {}",
                    reg.groupName(), streamKey, e.getMessage());
                counts.put(reg.groupName(), -1L);
            }
        }
        return counts;
    }

    /**
     * Gracefully shuts down all consumer threads.
     */
    @Override
    public void close() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        tenantConsumers.clear();
        log.info("TenantStreamConsumer shut down");
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void ensureConsumerGroup(String streamKey, String groupName) {
        JedisPooled jedis = jedisProvider.get();
        try {
            jedis.xgroupCreate(streamKey, groupName, StreamEntryID.LAST_ENTRY, true);
            log.debug("Created consumer group '{}' on stream '{}'", groupName, streamKey);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists on stream '{}'", groupName, streamKey);
            } else {
                throw new RuntimeException(
                    "Failed to create consumer group '" + groupName + "' on stream '" + streamKey + "'", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void consumeLoop(String tenantId, String streamKey, HandlerRegistration reg) {
        String consumerName = instanceId + "-" + tenantId;
        log.debug("Consumer started for tenant '{}', group '{}', stream '{}'",
            tenantId, reg.groupName(), streamKey);

        long batchCount = 0;
        long backoffMs = MIN_BACKOFF_MS;

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                JedisPooled jedis = jedisProvider.get();
                XReadGroupParams params = new XReadGroupParams()
                    .count(batchSize)
                    .block(blockMs);

                var result = jedis.xreadGroup(
                    reg.groupName(),
                    consumerName,
                    params,
                    Map.of(streamKey, StreamEntryID.UNRECEIVED_ENTRY)
                );

                // Reset backoff on successful read (even if no results)
                backoffMs = MIN_BACKOFF_MS;

                if (result == null || result.isEmpty()) {
                    continue;
                }

                for (var streamResult : result) {
                    String key = streamResult.getKey();
                    for (StreamEntry entry : streamResult.getValue()) {
                        processEntry(jedis, key, entry, tenantId, reg);
                    }
                }

                batchCount++;
                if (batchCount % 100 == 0) {
                    logConsumerLag(jedis, streamKey, reg.groupName(), tenantId);
                }
            } catch (Exception e) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("Error in consumer for tenant '{}', group '{}' — retrying after {}ms",
                    tenantId, reg.groupName(), backoffMs, e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }

        log.debug("Consumer stopped for tenant '{}', group '{}'", tenantId, reg.groupName());
    }

    private void processEntry(JedisPooled jedis, String streamKey, StreamEntry entry,
                              String tenantId, HandlerRegistration reg) {
        String messageId = entry.getID().toString();
        try {
            TenantContext.runWhere(tenantId, () ->
                reg.handler().onEvent(streamKey, messageId, entry.getFields()));
        } catch (Exception e) {
            log.warn("Handler failed for event {} on stream '{}' (tenant='{}', group='{}') — acknowledging to prevent blocking",
                messageId, streamKey, tenantId, reg.groupName(), e);
        } finally {
            try {
                jedis.xack(streamKey, reg.groupName(), entry.getID());
            } catch (Exception ackEx) {
                log.error("Failed to XACK message {} on stream '{}'", messageId, streamKey, ackEx);
            }
        }
    }

    private void logConsumerLag(JedisPooled jedis, String streamKey, String groupName, String tenantId) {
        try {
            long streamLen = jedis.xlen(streamKey);
            var pending = jedis.xpending(streamKey, groupName);
            log.debug("Consumer lag: tenant='{}', group='{}', streamLen={}, pending={}",
                tenantId, groupName, streamLen, pending.getTotal());
        } catch (Exception e) {
            // Observability failure is non-fatal
        }
    }
}
