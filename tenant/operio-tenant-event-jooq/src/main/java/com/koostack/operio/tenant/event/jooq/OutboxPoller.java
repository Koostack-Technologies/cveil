package com.koostack.operio.tenant.event.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.SubjectDsarService;
import com.koostack.operio.plugin.platform.jooq.crypto.FieldEncryptor;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import static com.koostack.operio.tenant.event.jooq.generated.tables.Event.EVENT;

/**
 * Polls the {@code event} table for unpublished rows and forwards them to
 * Redis via {@link RedisEventPublisher}. Implements the transactional
 * outbox pattern.
 */
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

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

    public OutboxPoller(DSLContext dsl, RedisEventPublisher redisPublisher,
                        Supplier<List<String>> tenantDiscovery) {
        this(dsl, redisPublisher, tenantDiscovery, DEFAULT_BATCH_SIZE, DEFAULT_POLL_INTERVAL_MS);
    }

    public OutboxPoller(
        DSLContext dsl,
        RedisEventPublisher redisPublisher,
        Supplier<List<String>> tenantDiscovery,
        int batchSize,
        long pollIntervalMs
    ) {
        this.dsl = dsl;
        this.redisPublisher = redisPublisher;
        this.tenantDiscovery = tenantDiscovery;
        this.batchSize = batchSize;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Injects the DSAR key repository and admin encryption key used to
     * decrypt per-subject DSAR keys for shreddable event payloads.
     */
    public void setSubjectDsarService(SubjectDsarService<?> service, String encryptionKey) {
        this.subjectDsarService = service;
        this.encryptionKey = encryptionKey;
    }

    public void start(int workerCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("OutboxPoller is already running");
            return;
        }
        workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "tenant-outbox-worker");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tenant-outbox-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
            this::discoverAndDispatch,
            0,
            pollIntervalMs,
            TimeUnit.MILLISECONDS
        );
        log.info("OutboxPoller started with {} workers, polling every {}ms", workerCount, pollIntervalMs);
    }

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

    private void discoverAndDispatch() {
        try {
            List<String> tenants = tenantDiscovery.get();
            if (!tenants.isEmpty()) {
                log.debug("Outbox discovered {} tenant(s): {}", tenants.size(), tenants);
            }
            for (String tenantId : tenants) {
                workers.submit(() ->
                    TenantContext.runWhere(tenantId, this::publishBatch)
                );
            }
        } catch (Exception e) {
            log.warn("Outbox discovery cycle failed — will retry", e);
        }
    }

    private void publishBatch() {
        String currentTenant = TenantContext.get();
        try {
            dsl.transaction(txConfig -> {
                DSLContext txDsl = txConfig.dsl();

                var batch = txDsl
                    .selectFrom(EVENT)
                    .where(EVENT.PUBLISHED.eq(false)
                        .and(EVENT.TENANT_ID.eq(currentTenant)))
                    .orderBy(EVENT.EVENT_ID.asc())
                    .limit(batchSize)
                    .forUpdate()
                    .skipLocked()
                    .fetch();

                log.debug("Outbox publishBatch for tenant '{}': found {} unpublished events",
                    currentTenant, batch.size());

                if (batch.isEmpty()) {
                    return;
                }

                for (var record : batch) {
                    String payload = record.getPayload();

                    // Digital shredder: decrypt shreddable payloads before Redis publish
                    if (Boolean.TRUE.equals(record.getShreddable())
                            && subjectDsarService != null && encryptionKey != null) {
                        UUID dsarId = record.getDsarId();
                        var encryptedKey = dsarId != null
                            ? subjectDsarService.findKeyByDsarId(dsarId)
                            : java.util.Optional.<String>empty();
                        if (encryptedKey.isEmpty()) {
                            // Key deleted — subject has been shredded (right to be forgotten).
                            // Do NOT publish to Redis — projections cannot handle a missing
                            // payload and would NPE.  Mark as published so the row isn't retried.
                            log.info("Skipping shredded event {} (dsar_id={})", record.getEventId(), dsarId);
                            continue;
                        } else {
                            String rawKey = FieldEncryptor.decrypt(encryptedKey.get(), this.encryptionKey);
                            // Payload is stored as a JSON string scalar ("cipher...").
                            // Parse the JSON string to recover the raw ciphertext.
                            String ciphertext = objectMapper.readValue(payload, String.class);
                            payload = FieldEncryptor.decrypt(ciphertext, rawKey);
                        }
                    }

                    Map<String, String> fields = new HashMap<>();
                    fields.put(TenantEvent.FIELD_EVENT_ID, String.valueOf(record.getEventId()));
                    fields.put(TenantEvent.FIELD_TENANT_ID, record.getTenantId());
                    fields.put(TenantEvent.FIELD_AGGREGATE_ID, record.getAggregateId());
                    fields.put(TenantEvent.FIELD_AGGREGATE_TYPE, record.getAggregateType());
                    fields.put(TenantEvent.FIELD_EVENT_TYPE, record.getEventType());
                    fields.put(TenantEvent.FIELD_VERSION, String.valueOf(record.getVersion()));
                    if (payload != null) {
                        fields.put(TenantEvent.FIELD_PAYLOAD, payload);
                    }
                    redisPublisher.publishRaw(fields);
                }

                List<Long> ids = batch.stream()
                    .map(r -> r.getEventId())
                    .toList();

                txDsl.update(EVENT)
                    .set(EVENT.PUBLISHED, true)
                    .where(EVENT.EVENT_ID.in(ids))
                    .execute();

                log.debug("Outbox relayed {} events to Redis", ids.size());
            });
        } catch (Exception e) {
            log.warn("Outbox publish failed — will retry", e);
        }
    }
}
