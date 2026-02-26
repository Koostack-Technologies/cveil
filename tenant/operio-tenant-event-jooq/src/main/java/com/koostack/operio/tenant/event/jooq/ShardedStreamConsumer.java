package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.plugin.redis.common.JedisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generic consumer that listens to all 16 sharded tenant Redis Streams
 * using {@code XREADGROUP} consumer groups.
 *
 * <p>Spawns one daemon thread per shard. Each thread performs a blocking
 * {@code XREADGROUP} (with configurable block timeout) and dispatches
 * received entries to a pluggable {@link StreamEventHandler}.
 *
 * <h2>Consumer Group Setup</h2>
 * <p>On startup, the consumer creates the consumer group on each stream
 * if it does not already exist (using {@code XGROUP CREATE ... MKSTREAM}).
 * The group starts consuming from the latest entry ({@code $}) so that
 * only new events are processed. To replay from the beginning, create
 * the group manually with ID {@code 0}.
 *
 * <h2>Poison-Message Safety</h2>
 * <p>If the handler throws, the message is still {@code XACK}'d to prevent
 * it from blocking the stream indefinitely. The exception is logged at
 * WARN level for monitoring / dead-letter-queue integration.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is safe for concurrent use. {@link #start()} and
 * {@link #stop()} are guarded by an {@link AtomicBoolean}.
 */
public class ShardedStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShardedStreamConsumer.class);

    private static final long DEFAULT_BLOCK_MS = 2000;
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final JedisProvider jedisProvider;
    private final StreamEventHandler handler;
    private final String groupName;
    private final String consumerName;
    private final long blockMs;
    private final int batchSize;

    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @param jedisProvider Redis connection provider
     * @param handler       callback for each consumed event
     * @param groupName     consumer group name (e.g. {@code "admin-projection"})
     * @param consumerName  unique consumer name within the group (e.g. hostname)
     */
    public ShardedStreamConsumer(
        JedisProvider jedisProvider,
        StreamEventHandler handler,
        String groupName,
        String consumerName
    ) {
        this(jedisProvider, handler, groupName, consumerName, DEFAULT_BLOCK_MS, DEFAULT_BATCH_SIZE);
    }

    public ShardedStreamConsumer(
        JedisProvider jedisProvider,
        StreamEventHandler handler,
        String groupName,
        String consumerName,
        long blockMs,
        int batchSize
    ) {
        this.jedisProvider = jedisProvider;
        this.handler = handler;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.blockMs = blockMs;
        this.batchSize = batchSize;
    }

    /**
     * Starts consuming from all 16 sharded streams.
     * Creates consumer groups if they do not exist.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("ShardedStreamConsumer is already running");
            return;
        }

        List<String> streamKeys = RedisEventPublisher.allStreamKeys();
        ensureConsumerGroups(streamKeys);

        executor = Executors.newFixedThreadPool(
            RedisEventPublisher.SHARD_COUNT,
            r -> {
                Thread t = new Thread(r, "stream-consumer-" + groupName);
                t.setDaemon(true);
                return t;
            }
        );

        for (String streamKey : streamKeys) {
            executor.submit(() -> consumeLoop(streamKey));
        }

        log.info("ShardedStreamConsumer [{}] started — {} shards, group={}, consumer={}",
            groupName, streamKeys.size(), groupName, consumerName);
    }

    /**
     * Gracefully shuts down all shard-reader threads.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("ShardedStreamConsumer [{}] stopped", groupName);
    }

    public boolean isRunning() {
        return running.get();
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void ensureConsumerGroups(List<String> streamKeys) {
        JedisPooled jedis = jedisProvider.get();
        for (String streamKey : streamKeys) {
            try {
                jedis.xgroupCreate(streamKey, groupName, StreamEntryID.LAST_ENTRY, true);
                log.debug("Created consumer group '{}' on stream '{}'", groupName, streamKey);
            } catch (Exception e) {
                // BUSYGROUP = group already exists — safe to ignore
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    log.debug("Consumer group '{}' already exists on stream '{}'", groupName, streamKey);
                } else {
                    throw new RuntimeException(
                        "Failed to create consumer group '" + groupName + "' on stream '" + streamKey + "'", e
                    );
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void consumeLoop(String streamKey) {
        log.debug("Shard reader started for stream '{}'", streamKey);

        while (running.get()) {
            try {
                JedisPooled jedis = jedisProvider.get();
                XReadGroupParams params = new XReadGroupParams()
                    .count(batchSize)
                    .block((int) blockMs);

                var result = jedis.xreadGroup(
                    groupName,
                    consumerName,
                    params,
                    Map.of(streamKey, StreamEntryID.UNRECEIVED_ENTRY)
                );

                if (result == null || result.isEmpty()) {
                    continue;
                }

                for (var streamResult : result) {
                    String key = streamResult.getKey();
                    for (StreamEntry entry : streamResult.getValue()) {
                        processEntry(jedis, key, entry);
                    }
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break; // shutting down
                }
                log.warn("Error in shard reader for stream '{}' — retrying after pause", streamKey, e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.debug("Shard reader stopped for stream '{}'", streamKey);
    }

    private void processEntry(JedisPooled jedis, String streamKey, StreamEntry entry) {
        String messageId = entry.getID().toString();
        try {
            handler.onEvent(streamKey, messageId, entry.getFields());
        } catch (Exception e) {
            log.warn("Handler failed for event {} on stream '{}' — acknowledging to prevent blocking",
                messageId, streamKey, e);
        } finally {
            // Always XACK to prevent the message from staying in the PEL
            try {
                jedis.xack(streamKey, groupName, entry.getID());
            } catch (Exception ackEx) {
                log.error("Failed to XACK message {} on stream '{}'", messageId, streamKey, ackEx);
            }
        }
    }
}
