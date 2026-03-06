package com.koostack.operio.plugin.redis.common.stream;

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
 * Abstract per-tenant stream consumer that spawns one virtual thread per
 * tenant per registered handler.
 *
 * <p>Subclasses provide the stream key resolution strategy via
 * {@link #streamKeyForTenant(String)}, allowing the same consumer
 * infrastructure to serve different stream types (events, metrics, etc.).
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
 * @see StreamHandler
 */
public abstract class AbstractStreamConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AbstractStreamConsumer.class);

    /**
     * A registered handler with its consumer group name.
     */
    public record HandlerRegistration(String groupName, StreamHandler handler) {}

    /** Minimum backoff on Redis errors (milliseconds). */
    private static final long MIN_BACKOFF_MS = 1_000;
    /** Maximum backoff on Redis errors (milliseconds). */
    private static final long MAX_BACKOFF_MS = 30_000;

    private JedisProvider jedisProvider;
    private final List<HandlerRegistration> handlers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, List<Future<?>>> tenantConsumers = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private int blockMs;
    private int batchSize;
    /** Unique per JVM instance — avoids consumer name collisions in multi-pod deployments. */
    private final String instanceId = System.getenv().getOrDefault("HOSTNAME", "operio")
        + "-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean running = true;

    /**
     * @param jedisProvider Redis connection provider
     * @param blockMs       XREADGROUP block timeout in milliseconds
     * @param batchSize     max entries per XREADGROUP call
     */
    protected AbstractStreamConsumer(JedisProvider jedisProvider, long blockMs, int batchSize) {
        if (blockMs < 0 || blockMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "blockMs must be between 0 and " + Integer.MAX_VALUE + ", got " + blockMs);
        }
        this.jedisProvider = jedisProvider;
        this.blockMs = (int) blockMs;
        this.batchSize = batchSize;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** No-args constructor required by CDI proxy subclasses. */
    protected AbstractStreamConsumer() {
        this.jedisProvider = null;
        this.blockMs = 0;
        this.batchSize = 0;
        this.executor = null;
    }

    /**
     * Returns the Redis stream key for the given tenant.
     *
     * @param tenantId the tenant identifier
     * @return the stream key
     */
    protected abstract String streamKeyForTenant(String tenantId);

    /**
     * Registers a handler with its consumer group name. Must be called
     * before any tenants are added.
     */
    public void registerHandler(String groupName, StreamHandler handler) {
        handlers.add(new HandlerRegistration(groupName, handler));
    }

    /**
     * Adds a tenant — creates consumer groups on the tenant's stream and
     * spawns one virtual thread per registered handler. Idempotent.
     */
    public void addTenant(String tenantId) {
        if (!running) {
            throw new IllegalStateException("Cannot add tenant — consumer is shut down");
        }
        tenantConsumers.computeIfAbsent(tenantId, id -> {
            String streamKey = streamKeyForTenant(id);
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
     * consumer threads are alive.
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
     */
    public Map<String, Long> getPendingCounts(String tenantId) {
        String streamKey = streamKeyForTenant(tenantId);
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
        log.info("{} shut down", getClass().getSimpleName());
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    protected JedisProvider getJedisProvider() {
        return jedisProvider;
    }

    private void ensureConsumerGroup(String streamKey, String groupName) {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            JedisPooled jedis = jedisProvider.get();
            try {
                jedis.xgroupCreate(streamKey, groupName, StreamEntryID.LAST_ENTRY, true);
                log.debug("Created consumer group '{}' on stream '{}'", groupName, streamKey);
                return;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    log.debug("Consumer group '{}' already exists on stream '{}'", groupName, streamKey);
                    return;
                }
                if (attempt < maxRetries) {
                    long sleepMs = 2000L * attempt;
                    log.warn("Failed to create consumer group '{}' on stream '{}' (attempt {}/{}): {} — retrying in {}ms",
                        groupName, streamKey, attempt, maxRetries, e.getMessage(), sleepMs);
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying consumer group creation", ie);
                    }
                } else {
                    throw new RuntimeException(
                        "Failed to create consumer group '" + groupName + "' on stream '" + streamKey +
                            "' after " + maxRetries + " attempts", e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void consumeLoop(String tenantId, String streamKey, HandlerRegistration reg) {
        String consumerName = instanceId + "-" + tenantId;
        log.debug("Consumer loop starting for tenant '{}', group '{}', stream '{}', consumer='{}'",
            tenantId, reg.groupName(), streamKey, consumerName);

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

                backoffMs = MIN_BACKOFF_MS;

                if (result == null || result.isEmpty()) {
                    continue;
                }

                int entryCount = result.stream().mapToInt(r -> r.getValue().size()).sum();
                log.debug("Consumer read {} entries for tenant '{}', group '{}'",
                    entryCount, tenantId, reg.groupName());

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
            log.warn("Handler failed for entry {} on stream '{}' (tenant='{}', group='{}') — acknowledging to prevent blocking",
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
