package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.redis.common.JedisProvider;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Fixed-shard stream consumer that distributes tenant events across a configurable number of Redis
 * streams ({@code operio:stream:shard:{0..N-1}}).
 *
 * <p>Unlike the per-tenant {@link TenantStreamConsumer}, this consumer uses a fixed number of shard
 * streams regardless of tenant count. Each shard has one reader thread per registered handler group,
 * and incoming messages are dispatched to a shared virtual thread pool for per-tenant parallel
 * processing.
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * Shard 0 ──reader(graph-projection)──┐
 *         ──reader(admin-projection)──┤
 * Shard 1 ──reader(graph-projection)──┤
 *         ──reader(admin-projection)──┼──▶ Per-Tenant Ordered Executors
 *   ...                               │     → FIFO processing per tenant × handler
 * Shard 7 ──reader(graph-projection)──┤     → TenantContext.runWhere(tenantId, handler::onEvent)
 *         ──reader(admin-projection)──┘     → XACK after handler completes
 * </pre>
 *
 * <h2>Connection Usage</h2>
 *
 * <p>Total XREADGROUP connections = shardCount × handlerCount (default 8 × 2 = 16). This is O(1)
 * regardless of tenant count, solving the connection pool exhaustion that occurred with per-tenant
 * streams.
 *
 * <h2>Head-of-Line Blocking Prevention</h2>
 *
 * <p>Shard reader threads do not process messages inline. Instead, each message is dispatched to a
 * per-tenant ordered executor (single-thread virtual thread executor) that processes events in FIFO
 * order. This guarantees correct event ordering within a tenant while allowing different tenants to
 * proceed in parallel. A slow handler for one tenant does not block processing of other tenants on
 * the same shard.
 *
 * @see RedisEventPublisher#shardStreamKey(String)
 */
public class ShardedStreamConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ShardedStreamConsumer.class);

    private static final long MIN_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final String STREAM_KEY_PREFIX = "operio:stream:shard:";

    /** A registered handler with its consumer group name. */
    record HandlerRegistration(String groupName, StreamEventHandler handler) {}

    private final JedisProvider jedisProvider;
    private final int shardCount;
    private final int blockMs;
    private final int batchSize;
    private final List<HandlerRegistration> handlers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Unique per JVM instance — avoids consumer name collisions in multi-pod deployments. */
    private final String instanceId =
            System.getenv().getOrDefault("HOSTNAME", "operio")
                    + "-"
                    + UUID.randomUUID().toString().substring(0, 8);

    /** Reader threads (one per shard × handler). */
    private ExecutorService readerExecutor;

    private final List<Future<?>> readerFutures = new ArrayList<>();

    /**
     * Per-tenant-per-handler ordered executors. Each executor is a single-thread virtual-thread
     * executor that processes submitted tasks in FIFO order. This guarantees that events for the
     * same tenant within the same handler group are processed in stream order, while different
     * tenants proceed in parallel. Key format: {@code groupName + ":" + tenantId}.
     */
    private final ConcurrentHashMap<String, ExecutorService> tenantExecutors =
            new ConcurrentHashMap<>();

    /**
     * @param jedisProvider Redis connection provider (admin pool)
     * @param shardCount number of shard streams (must match publisher's shard count)
     * @param blockMs XREADGROUP block timeout in milliseconds
     * @param batchSize max entries per XREADGROUP call
     */
    public ShardedStreamConsumer(
            JedisProvider jedisProvider, int shardCount, long blockMs, int batchSize) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1, got " + shardCount);
        }
        if (blockMs < 0 || blockMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "blockMs must be between 0 and " + Integer.MAX_VALUE + ", got " + blockMs);
        }
        this.jedisProvider = jedisProvider;
        this.shardCount = shardCount;
        this.blockMs = (int) blockMs;
        this.batchSize = batchSize;
    }

    /**
     * Registers a handler with its consumer group name. Must be called before {@link #start()}.
     *
     * @param groupName the Redis consumer group name (e.g. "graph-projection")
     * @param handler the event handler
     */
    public void registerHandler(String groupName, StreamEventHandler handler) {
        if (running.get()) {
            throw new IllegalStateException("Cannot register handlers after start()");
        }
        handlers.add(new HandlerRegistration(groupName, handler));
    }

    /**
     * Starts all shard reader threads and the dispatch pool. Creates consumer groups on every shard
     * for every registered handler, then spawns one reader thread per shard × handler.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("ShardedStreamConsumer is already running");
            return;
        }
        if (handlers.isEmpty()) {
            log.warn("ShardedStreamConsumer started with no handlers registered");
        }

        readerExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Create consumer groups on all shards for all handlers
        for (int shard = 0; shard < shardCount; shard++) {
            String streamKey = streamKeyForShard(shard);
            for (HandlerRegistration reg : handlers) {
                ensureConsumerGroup(streamKey, reg.groupName());
            }
        }

        // Start reader threads: one per shard × handler
        for (int shard = 0; shard < shardCount; shard++) {
            String streamKey = streamKeyForShard(shard);
            for (HandlerRegistration reg : handlers) {
                final int shardIndex = shard;
                Future<?> future =
                        readerExecutor.submit(() -> readerLoop(shardIndex, streamKey, reg));
                readerFutures.add(future);
            }
        }

        log.info(
                "ShardedStreamConsumer started: {} shards × {} handlers = {} reader threads,"
                        + " instance={}",
                shardCount,
                handlers.size(),
                readerFutures.size(),
                instanceId);
    }

    /** Returns the number of shard streams. */
    public int shardCount() {
        return shardCount;
    }

    /** Returns true if the consumer is running. */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // Cancel reader threads
        for (Future<?> f : readerFutures) {
            f.cancel(true);
        }
        readerFutures.clear();

        // Shut down executors
        readerExecutor.shutdown();
        for (ExecutorService exec : tenantExecutors.values()) {
            exec.shutdown();
        }
        try {
            if (!readerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                readerExecutor.shutdownNow();
            }
            for (ExecutorService exec : tenantExecutors.values()) {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            readerExecutor.shutdownNow();
            for (ExecutorService exec : tenantExecutors.values()) {
                exec.shutdownNow();
            }
            Thread.currentThread().interrupt();
        }
        tenantExecutors.clear();

        log.info("ShardedStreamConsumer shut down (instance={})", instanceId);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    String streamKeyForShard(int shard) {
        return STREAM_KEY_PREFIX + shard;
    }

    private static boolean isNoGroupError(Exception e) {
        return e instanceof redis.clients.jedis.exceptions.JedisDataException
                && e.getMessage() != null
                && e.getMessage().contains("NOGROUP");
    }

    /**
     * Per-shard-per-handler reader loop. Blocks on XREADGROUP, dispatches each message to the
     * virtual thread pool for handler invocation + ACK.
     */
    @SuppressWarnings("unchecked")
    private void readerLoop(int shard, String streamKey, HandlerRegistration reg) {
        String consumerName = instanceId + "-shard-" + shard;
        log.debug(
                "Shard reader starting: shard={}, group='{}', stream='{}', consumer='{}'",
                shard,
                reg.groupName(),
                streamKey,
                consumerName);

        long backoffMs = MIN_BACKOFF_MS;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                JedisPooled jedis = jedisProvider.get();
                XReadGroupParams params =
                        new XReadGroupParams().count(batchSize).block(this.blockMs);

                var result =
                        jedis.xreadGroup(
                                reg.groupName(),
                                consumerName,
                                params,
                                Map.of(streamKey, StreamEntryID.UNRECEIVED_ENTRY));

                backoffMs = MIN_BACKOFF_MS;

                if (result == null || result.isEmpty()) {
                    continue;
                }

                for (var streamResult : result) {
                    for (StreamEntry entry : streamResult.getValue()) {
                        dispatchEntry(streamKey, entry, reg);
                    }
                }

            } catch (Exception e) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                // NOGROUP means the stream or consumer group was deleted (e.g. by
                // FLUSHDB during test runs).  Re-create the group and retry
                // immediately instead of entering exponential backoff.
                if (isNoGroupError(e)) {
                    log.info(
                            "Recreating consumer group '{}' on stream '{}' (shard={}) after"
                                    + " NOGROUP error",
                            reg.groupName(),
                            streamKey,
                            shard);
                    ensureConsumerGroup(streamKey, reg.groupName());
                    backoffMs = MIN_BACKOFF_MS;
                    continue;
                }
                log.warn(
                        "Error in shard reader (shard={}, group='{}') — retrying after {}ms",
                        shard,
                        reg.groupName(),
                        backoffMs,
                        e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }

        log.debug("Shard reader stopped: shard={}, group='{}'", shard, reg.groupName());
    }

    /**
     * Dispatches a single stream entry to the per-tenant ordered executor. Each tenant × handler
     * combination has its own single-thread executor, guaranteeing FIFO processing order. Different
     * tenants run in parallel on separate virtual threads.
     */
    private void dispatchEntry(String streamKey, StreamEntry entry, HandlerRegistration reg) {
        String tenantId = entry.getFields().get("tenantId");

        // Per-tenant-per-handler executor ensures FIFO event ordering within a tenant.
        String executorKey =
                reg.groupName() + ":" + (tenantId != null ? tenantId : "__none__");
        ExecutorService executor =
                tenantExecutors.computeIfAbsent(
                        executorKey,
                        k -> Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()));

        executor.submit(
                () -> {
                    String messageId = entry.getID().toString();
                    try {
                        if (tenantId != null) {
                            TenantContext.runWhere(
                                    tenantId,
                                    () ->
                                            reg.handler()
                                                    .onEvent(
                                                            streamKey,
                                                            messageId,
                                                            entry.getFields()));
                        } else {
                            reg.handler().onEvent(streamKey, messageId, entry.getFields());
                        }
                    } catch (Exception e) {
                        log.warn(
                                "Handler failed for entry {} on stream '{}' (tenant='{}', "
                                        + "group='{}') — acknowledging to prevent blocking",
                                messageId,
                                streamKey,
                                tenantId,
                                reg.groupName(),
                                e);
                    } finally {
                        try {
                            JedisPooled jedis = jedisProvider.get();
                            jedis.xack(streamKey, reg.groupName(), entry.getID());
                        } catch (Exception ackEx) {
                            log.error(
                                    "Failed to XACK message {} on stream '{}'",
                                    messageId,
                                    streamKey,
                                    ackEx);
                        }
                    }
                });
    }

    private void ensureConsumerGroup(String streamKey, String groupName) {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            JedisPooled jedis = jedisProvider.get();
            try {
                jedis.xgroupCreate(streamKey, groupName, new StreamEntryID(), true);
                log.debug("Created consumer group '{}' on stream '{}'", groupName, streamKey);
                return;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    log.debug(
                            "Consumer group '{}' already exists on stream '{}'",
                            groupName,
                            streamKey);
                    return;
                }
                if (attempt < maxRetries) {
                    long sleepMs = 2000L * attempt;
                    log.warn(
                            "Failed to create consumer group '{}' on stream '{}' (attempt {}/{}):"
                                    + " {} — retrying in {}ms",
                            groupName,
                            streamKey,
                            attempt,
                            maxRetries,
                            e.getMessage(),
                            sleepMs);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(
                                "Interrupted while retrying consumer group creation", ie);
                    }
                } else {
                    throw new RuntimeException(
                            "Failed to create consumer group '"
                                    + groupName
                                    + "' on stream '"
                                    + streamKey
                                    + "' after "
                                    + maxRetries
                                    + " attempts",
                            e);
                }
            }
        }
    }
}
