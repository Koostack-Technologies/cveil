package com.koostack.operio.plugin.event.jooq;

import static com.koostack.operio.plugin.event.jooq.generated.tables.Event.EVENT;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.SubjectDsarService;
import com.koostack.operio.plugin.platform.jooq.crypto.FieldEncryptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the {@code event} table for unpublished rows and forwards them to Redis. Implements the
 * <em>transactional outbox</em> pattern: the {@code publish} call only writes to the database; this
 * poller asynchronously relays events to Redis in the background.
 *
 * <p>Each poll cycle discovers active tenants via the provided {@code tenantDiscovery} supplier,
 * then processes each tenant's outbox within a {@link TenantContext} scope so that RLS filters
 * queries to the correct tenant's events, and the tenant-aware Redis provider authenticates as the
 * correct ACL user.
 *
 * <p>Supports the digital shredder pattern: shreddable events are decrypted with their per-subject
 * DSAR key before publishing to Redis. If the DSAR key has been deleted (subject exercised right to
 * be forgotten), the event is skipped but marked as published.
 */
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final long DEFAULT_POLL_INTERVAL_MS = 500;

    private final DSLContext dsl;
    private final RedisEventPublisher redisPublisher;
    private final Supplier<List<String>> tenantDiscovery;
    private final int batchSize;
    private final long pollIntervalMs;

    private volatile SubjectDsarService<?> subjectDsarService;
    private volatile String encryptionKey;

    private ScheduledExecutorService scheduler;
    private ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxPoller(
            DSLContext dsl,
            RedisEventPublisher redisPublisher,
            Supplier<List<String>> tenantDiscovery) {
        this(dsl, redisPublisher, tenantDiscovery, DEFAULT_BATCH_SIZE, DEFAULT_POLL_INTERVAL_MS);
    }

    public OutboxPoller(
            DSLContext dsl,
            RedisEventPublisher redisPublisher,
            Supplier<List<String>> tenantDiscovery,
            int batchSize,
            long pollIntervalMs) {
        this.dsl = dsl;
        this.redisPublisher = redisPublisher;
        this.tenantDiscovery = tenantDiscovery;
        this.batchSize = batchSize;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Injects the DSAR key repository and encryption key for digital shredder support. When set,
     * shreddable events are decrypted before Redis publish; if the key is deleted (subject
     * shredded), the event is skipped.
     */
    public void setSubjectDsarService(SubjectDsarService<?> service, String encryptionKey) {
        this.subjectDsarService = service;
        this.encryptionKey = encryptionKey;
    }

    /** Starts the outbox poller with the given number of worker threads. */
    public void start(int workerCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("OutboxPoller is already running");
            return;
        }
        workers =
                Executors.newFixedThreadPool(
                        workerCount,
                        r -> {
                            Thread t = new Thread(r, "outbox-worker");
                            t.setDaemon(true);
                            return t;
                        });
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "outbox-scheduler");
                            t.setDaemon(true);
                            return t;
                        });
        scheduler.scheduleWithFixedDelay(
                this::discoverAndDispatch, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info(
                "OutboxPoller started with {} workers, polling every {}ms",
                workerCount,
                pollIntervalMs);
    }

    /** Gracefully shuts down the poller, waiting up to 5 seconds for in-flight work to complete. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdown();
        workers.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("OutboxPoller stopped");
    }

    /**
     * Discovers active tenants and submits a per-tenant publish task to the worker pool, each
     * wrapped in the appropriate {@link TenantContext}.
     */
    private void discoverAndDispatch() {
        try {
            List<String> tenants = tenantDiscovery.get();
            for (String tenantId : tenants) {
                workers.submit(() -> TenantContext.runWhere(tenantId, this::publishBatch));
            }
        } catch (Exception e) {
            log.warn("Outbox discovery cycle failed — will retry", e);
        }
    }

    /**
     * Claims and publishes a batch of unpublished events. Uses {@code FOR UPDATE SKIP LOCKED} so
     * concurrent workers (across nodes) do not block each other. Routes each event to the correct
     * Redis stream based on the tenant_id from TenantContext.
     */
    private void publishBatch() {
        try {
            String tenantId = TenantContext.get();
            String streamKey =
                    "platform".equals(tenantId)
                            ? redisPublisher.platformStreamKey()
                            : redisPublisher.tenantStreamKey(tenantId);

            dsl.transaction(
                    txConfig -> {
                        DSLContext txDsl = txConfig.dsl();

                        var batch =
                                txDsl.selectFrom(EVENT)
                                        .where(
                                                EVENT.PUBLISHED
                                                        .eq(false)
                                                        .and(EVENT.TENANT_ID.eq(tenantId)))
                                        .orderBy(EVENT.EVENT_ID.asc())
                                        .limit(batchSize)
                                        .forUpdate()
                                        .skipLocked()
                                        .fetch();

                        if (batch.isEmpty()) {
                            return;
                        }

                        List<Long> publishedIds = new ArrayList<>();

                        for (var record : batch) {
                            String payload = record.getPayload();

                            // Digital shredder: decrypt shreddable payloads before Redis publish
                            if (Boolean.TRUE.equals(record.getShreddable())
                                    && subjectDsarService != null
                                    && encryptionKey != null) {
                                UUID dsarId = record.getDsarId();
                                var encryptedKey =
                                        dsarId != null
                                                ? subjectDsarService.findKeyByDsarId(dsarId)
                                                : java.util.Optional.<String>empty();
                                if (encryptedKey.isEmpty()) {
                                    log.info(
                                            "Skipping shredded event {} (dsar_id={})",
                                            record.getEventId(),
                                            dsarId);
                                    publishedIds.add(record.getEventId());
                                    continue;
                                }
                                String rawKey =
                                        FieldEncryptor.decrypt(
                                                encryptedKey.get(), this.encryptionKey);
                                String ciphertext = objectMapper.readValue(payload, String.class);
                                payload = FieldEncryptor.decrypt(ciphertext, rawKey);
                            }

                            Map<String, String> fields = new HashMap<>();
                            fields.put(
                                    PlatformEvent.FIELD_EVENT_ID,
                                    String.valueOf(record.getEventId()));
                            fields.put(PlatformEvent.FIELD_AGGREGATE_ID, record.getAggregateId());
                            fields.put(
                                    PlatformEvent.FIELD_AGGREGATE_TYPE, record.getAggregateType());
                            fields.put(PlatformEvent.FIELD_EVENT_TYPE, record.getEventType());
                            fields.put(
                                    PlatformEvent.FIELD_VERSION,
                                    String.valueOf(record.getVersion()));
                            if (payload != null) {
                                fields.put(PlatformEvent.FIELD_PAYLOAD, payload);
                            }
                            if (record.getTenantId() != null) {
                                fields.put("tenantId", record.getTenantId());
                            }
                            redisPublisher.publishRaw(streamKey, fields);
                            publishedIds.add(record.getEventId());
                        }

                        if (!publishedIds.isEmpty()) {
                            txDsl.update(EVENT)
                                    .set(EVENT.PUBLISHED, true)
                                    .where(EVENT.EVENT_ID.in(publishedIds))
                                    .execute();
                        }

                        log.debug(
                                "Outbox relayed {} events to Redis stream {}",
                                publishedIds.size(),
                                streamKey);
                    });
        } catch (Exception e) {
            log.warn("Outbox publish failed — will retry", e);
        }
    }
}
