package com.koostack.operio.common.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.io.Closeable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Maintains a Caffeine-backed set of active tenant IDs, fed by the platform event stream.
 *
 * <p>Hydrated at construction from an initial set (derived from the platform event log by the
 * caller) and kept current by subscribing to the {@code operio:platform-events} Redis stream with
 * its own consumer group ({@code tenant-registry}).
 *
 * <p>Components that need the active tenant list (e.g. OutboxPoller, TenantStreamConsumer) take a
 * {@code TenantRegistry} via constructor injection and call {@link #activeTenants()}.
 *
 * <p>This class is thread-safe. The underlying Caffeine cache handles concurrent reads and writes.
 */
public class TenantRegistry implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(TenantRegistry.class);

    static final String PLATFORM_STREAM_KEY = "operio:platform-events";
    static final String DEFAULT_CONSUMER_GROUP = "tenant-registry";
    private static final int BLOCK_MS = 500;
    private static final int MAX_RETRIES = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Active tenants. Caffeine is used as a concurrent set (value is always {@link Boolean#TRUE}).
     * No eviction — entries are only removed by explicit lifecycle events.
     */
    private final Cache<String, Boolean> activeTenants;

    private final JedisProvider jedisProvider;
    private final String consumerGroup;
    private final String consumerName;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a registry pre-loaded with the given tenants, using the default consumer group.
     *
     * @param initialActiveTenants tenant IDs known to be active at startup (hydrated from the
     *     platform event log by the caller)
     * @param jedisProvider provides pooled Redis connections for stream consumption
     */
    public TenantRegistry(Set<String> initialActiveTenants, JedisProvider jedisProvider) {
        this(initialActiveTenants, jedisProvider, DEFAULT_CONSUMER_GROUP);
    }

    /**
     * Creates a registry pre-loaded with the given tenants, using a custom consumer group. Each
     * consumer group tracks its own read position independently, so multiple registries (e.g.
     * production + integration test) can coexist on the same stream without stealing messages.
     *
     * @param initialActiveTenants tenant IDs known to be active at startup
     * @param jedisProvider provides pooled Redis connections for stream consumption
     * @param consumerGroup the Redis consumer group name to use
     */
    public TenantRegistry(
            Set<String> initialActiveTenants, JedisProvider jedisProvider, String consumerGroup) {
        this.jedisProvider = jedisProvider;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerGroup + "-0";
        this.activeTenants = Caffeine.newBuilder().build();
        for (String tid : initialActiveTenants) {
            this.activeTenants.put(tid, Boolean.TRUE);
        }
        this.executor =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "tenant-registry-consumer");
                            t.setDaemon(true);
                            return t;
                        });
        LOG.info(
                "TenantRegistry created with {} initial tenants: {} (group={})",
                initialActiveTenants.size(),
                initialActiveTenants,
                consumerGroup);
    }

    /**
     * Starts consuming the platform event stream for tenant lifecycle updates. Creates the consumer
     * group if it does not already exist.
     *
     * <p>Safe to call multiple times — subsequent calls are no-ops.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            LOG.debug("TenantRegistry already started");
            return;
        }
        ensureConsumerGroup();
        executor.submit(this::consumeLoop);
        LOG.info("TenantRegistry started — consuming platform stream for tenant lifecycle events");
    }

    /**
     * Returns the current set of active tenant IDs.
     *
     * @return unmodifiable snapshot of active tenant IDs
     */
    public Set<String> activeTenants() {
        return Collections.unmodifiableSet(activeTenants.asMap().keySet());
    }

    /**
     * Checks whether a specific tenant is currently active.
     *
     * @param tenantId the tenant to check
     * @return true if the tenant is in the active set
     */
    public boolean isActive(String tenantId) {
        return activeTenants.getIfPresent(tenantId) != null;
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("TenantRegistry closed");
    }

    // -- Platform event handling ------------------------------------------------

    /**
     * Processes a platform event, updating the active tenant set if the event is a tenant lifecycle
     * event. Called internally from the consumer loop and exposed for testing.
     */
    void onPlatformEvent(String eventType, String payload) {
        if (eventType == null) return;

        switch (eventType) {
            case CoreEventTypes.TENANT_CREATED, CoreEventTypes.TENANT_ACTIVATED -> {
                String tenantId = extractTenantId(payload);
                if (tenantId != null) {
                    activeTenants.put(tenantId, Boolean.TRUE);
                    LOG.info("TenantRegistry: tenant '{}' added (event={})", tenantId, eventType);
                }
            }
            case CoreEventTypes.TENANT_SUSPENDED, CoreEventTypes.TENANT_DELETED -> {
                String tenantId = extractTenantId(payload);
                if (tenantId != null) {
                    activeTenants.invalidate(tenantId);
                    LOG.info("TenantRegistry: tenant '{}' removed (event={})", tenantId, eventType);
                }
            }
            default -> {
                // Not a tenant lifecycle event — ignore
            }
        }
    }

    // -- Internal ---------------------------------------------------------------

    private void consumeLoop() {
        while (running.get()) {
            try {
                JedisPooled jedis = jedisProvider.get();
                XReadGroupParams params = new XReadGroupParams().count(10).block(BLOCK_MS);
                var results =
                        jedis.xreadGroup(
                                consumerGroup,
                                consumerName,
                                params,
                                Map.of(PLATFORM_STREAM_KEY, StreamEntryID.UNRECEIVED_ENTRY));

                if (results == null || results.isEmpty()) continue;

                for (var streamResult : results) {
                    for (StreamEntry entry : streamResult.getValue()) {
                        try {
                            String eventType = entry.getFields().get("eventType");
                            String payload = entry.getFields().get("payload");
                            onPlatformEvent(eventType, payload);
                        } catch (Exception e) {
                            LOG.warn(
                                    "TenantRegistry: failed to process event {}: {}",
                                    entry.getID(),
                                    e.getMessage());
                        }
                        // Always ACK to prevent redelivery
                        jedis.xack(PLATFORM_STREAM_KEY, consumerGroup, entry.getID());
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    LOG.warn("TenantRegistry: consumer loop error — retrying in 1s", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        LOG.debug("TenantRegistry: consumer loop exited");
    }

    private void ensureConsumerGroup() {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                JedisPooled jedis = jedisProvider.get();
                jedis.xgroupCreate(
                        PLATFORM_STREAM_KEY, consumerGroup, new StreamEntryID(), true); // MKSTREAM
                LOG.info(
                        "TenantRegistry: created consumer group '{}' on '{}'",
                        consumerGroup,
                        PLATFORM_STREAM_KEY);
                return;
            } catch (JedisDataException e) {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    LOG.debug("TenantRegistry: consumer group '{}' already exists", consumerGroup);
                    return;
                }
                LOG.warn(
                        "TenantRegistry: failed to create consumer group (attempt {}/{})",
                        attempt,
                        MAX_RETRIES,
                        e);
            } catch (Exception e) {
                LOG.warn(
                        "TenantRegistry: failed to create consumer group (attempt {}/{})",
                        attempt,
                        MAX_RETRIES,
                        e);
            }
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new RuntimeException(
                "TenantRegistry: failed to create consumer group after "
                        + MAX_RETRIES
                        + " attempts");
    }

    private static String extractTenantId(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode tid = node.get("tenantId");
            return tid != null && !tid.isNull() ? tid.asText() : null;
        } catch (Exception e) {
            LOG.warn("TenantRegistry: failed to extract tenantId from payload", e);
            return null;
        }
    }
}
