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
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the {@code event} table for unpublished rows and forwards them to Redis. Implements the
 * <em>transactional outbox</em> pattern: the {@code publish} call only writes to the database; this
 * poller asynchronously relays events to Redis in the background.
 *
 * <p>Each poll cycle discovers tenants with pending events directly from the event table using a
 * {@code SELECT DISTINCT tenant_id WHERE published = false} query, then processes each tenant's
 * outbox within a {@link TenantContext} scope so that RLS filters queries to the correct tenant's
 * events, and the tenant-aware Redis provider authenticates as the correct ACL user.
 *
 * <p>The discovery query uses a reserved sentinel tenant ID ({@code __discovery__}) that is
 * matched by a dedicated RLS policy, allowing cross-tenant visibility for the discovery SELECT
 * only.
 *
 * <p>Supports the digital shredder pattern: shreddable events are decrypted with their per-subject
 * DSAR key before publishing to Redis. If the DSAR key has been deleted (subject exercised right to
 * be forgotten), the event is skipped but marked as published.
 */
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final long DEFAULT_POLL_INTERVAL_MS = 250;

    /**
     * Reserved sentinel tenant ID used for cross-tenant discovery queries. Matched by a dedicated
     * RLS policy ({@code outbox_discovery}) on the event table that allows SELECT on all rows.
     */
    static final String DISCOVERY_TENANT = "__discovery__";

    private final DSLContext dsl;
    private final RedisEventPublisher redisPublisher;
    private final int batchSize;
    private final long pollIntervalMs;

    private volatile SubjectDsarService<?> subjectDsarService;
    private volatile String encryptionKey;

    private ScheduledExecutorService scheduler;
    private ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxPoller(
            DSLContext dsl,
            RedisEventPublisher redisPublisher) {
        this(dsl, redisPublisher, DEFAULT_BATCH_SIZE, DEFAULT_POLL_INTERVAL_MS);
    }

    public OutboxPoller(
            DSLContext dsl,
            RedisEventPublisher redisPublisher,
            int batchSize,
            long pollIntervalMs) {
        this.dsl = dsl;
        this.redisPublisher = redisPublisher;
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

    /**
     * Starts the outbox poller with virtual threads for per-tenant parallelism. Each tenant's
     * publish batch runs on its own virtual thread (~1KB stack), eliminating the fixed-thread
     * bottleneck when many tenants are active concurrently.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("OutboxPoller is already running");
            return;
        }
        workers = Executors.newVirtualThreadPerTaskExecutor();
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "outbox-scheduler");
                            t.setDaemon(true);
                            return t;
                        });
        scheduler.scheduleWithFixedDelay(
                this::discoverAndDispatch, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("OutboxPoller started with virtual threads, polling every {}ms", pollIntervalMs);
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
     * Discovers tenants with unpublished events directly from the event table, then submits a
     * per-tenant publish task to the worker pool, each wrapped in the appropriate {@link
     * TenantContext}.
     */
    private void discoverAndDispatch() {
        try {
            List<String> tenants = discoverTenantsWithPendingEvents();
            for (String tenantId : tenants) {
                workers.submit(() -> TenantContext.runWhere(tenantId, this::publishBatch));
            }
        } catch (Exception e) {
            log.warn("Outbox discovery cycle failed — will retry", e);
        }
    }

    /**
     * Queries the event table for distinct tenant IDs with unpublished events. Uses the {@link
     * #DISCOVERY_TENANT} sentinel to activate the {@code outbox_discovery} RLS policy, which
     * allows cross-tenant SELECT visibility for this query only.
     */
    private List<String> discoverTenantsWithPendingEvents() {
        try {
            return TenantContext.callWhere(
                    DISCOVERY_TENANT,
                    () ->
                            dsl.selectDistinct(EVENT.TENANT_ID)
                                    .from(EVENT)
                                    .where(EVENT.PUBLISHED.eq(false))
                                    .fetch(EVENT.TENANT_ID));
        } catch (Exception e) {
            log.warn("Event-table tenant discovery failed — falling back to 'platform'", e);
            return List.of("platform");
        }
    }

    /**
     * Claims and publishes a batch of unpublished events. Uses two phases to minimize the time a DB
     * transaction (and its row locks) are held:
     *
     * <p><b>Phase 1 — Claim:</b> A short transaction SELECTs unpublished rows with {@code FOR
     * UPDATE SKIP LOCKED} and immediately marks them as published. Row locks are released when the
     * transaction commits (typically &lt; 5 ms).
     *
     * <p><b>Phase 2 — Publish:</b> Outside the transaction, performs DSAR key lookup/decryption (if
     * needed) and publishes to Redis via pipelined XADD.
     *
     * <p>If the Redis publish fails, the events are already marked published and will NOT be
     * retried. This is acceptable because the shard consumer is idempotent and because losing a
     * relay under transient Redis failure is preferable to holding row locks during external I/O.
     */
    private void publishBatch() {
        try {
            String tenantId = TenantContext.get();
            String streamKey =
                    "platform".equals(tenantId)
                            ? redisPublisher.platformStreamKey()
                            : redisPublisher.shardStreamKey(tenantId);

            // Phase 1 — Claim: short transaction to SELECT + mark published.
            // Row locks are held only for the duration of this lambda.
            record ClaimedEvent(Long eventId, String aggregateId, String aggregateType,
                                String eventType, Long version, String payload,
                                String eventTenantId, Boolean shreddable, UUID dsarId) {}

            List<ClaimedEvent> claimed = new ArrayList<>();

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

                        List<Long> ids = new ArrayList<>(batch.size());
                        for (var record : batch) {
                            ids.add(record.getEventId());
                            claimed.add(new ClaimedEvent(
                                    record.getEventId(),
                                    record.getAggregateId(),
                                    record.getAggregateType(),
                                    record.getEventType(),
                                    record.getVersion(),
                                    record.getPayload(),
                                    record.getTenantId(),
                                    record.getShreddable(),
                                    record.getDsarId()));
                        }

                        txDsl.update(EVENT)
                                .set(EVENT.PUBLISHED, true)
                                .where(EVENT.EVENT_ID.in(ids))
                                .execute();
                    });

            if (claimed.isEmpty()) {
                return;
            }

            // Phase 2 — Publish: DSAR decryption + Redis pipeline, outside the DB transaction.
            List<Map<String, String>> fieldsBatch = new ArrayList<>();

            for (var record : claimed) {
                String payload = record.payload();

                // Digital shredder: decrypt shreddable payloads before Redis publish
                if (Boolean.TRUE.equals(record.shreddable())
                        && subjectDsarService != null
                        && encryptionKey != null) {
                    UUID dsarId = record.dsarId();
                    var encryptedKey =
                            dsarId != null
                                    ? subjectDsarService.findKeyByDsarId(dsarId)
                                    : java.util.Optional.<String>empty();
                    if (encryptedKey.isEmpty()) {
                        log.info(
                                "Skipping shredded event {} (dsar_id={})",
                                record.eventId(),
                                dsarId);
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
                        String.valueOf(record.eventId()));
                fields.put(PlatformEvent.FIELD_AGGREGATE_ID, record.aggregateId());
                fields.put(
                        PlatformEvent.FIELD_AGGREGATE_TYPE, record.aggregateType());
                fields.put(PlatformEvent.FIELD_EVENT_TYPE, record.eventType());
                fields.put(
                        PlatformEvent.FIELD_VERSION,
                        String.valueOf(record.version()));
                if (payload != null) {
                    fields.put(PlatformEvent.FIELD_PAYLOAD, payload);
                }
                if (record.eventTenantId() != null) {
                    fields.put("tenantId", record.eventTenantId());
                }
                fieldsBatch.add(fields);
            }

            if (!fieldsBatch.isEmpty()) {
                redisPublisher.publishBatchPipelined(streamKey, fieldsBatch);
            }

            log.debug(
                    "Outbox relayed {} events to Redis stream {}",
                    fieldsBatch.size(),
                    streamKey);
        } catch (Exception e) {
            log.warn("Outbox publish failed — will retry", e);
        }
    }
}
