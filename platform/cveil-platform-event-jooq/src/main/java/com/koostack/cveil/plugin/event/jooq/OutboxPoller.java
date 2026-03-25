package com.koostack.cveil.plugin.event.jooq;

import static com.koostack.cveil.plugin.event.jooq.generated.tables.Event.EVENT;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.cveil.common.crypto.FieldEncryptor;
import com.koostack.cveil.core.event.models.PlatformEvent;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.SubjectDsarService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Relays unpublished rows from the {@code event} table to Redis. Implements the <em>transactional
 * outbox</em> pattern: the {@code publish} call only writes to the database; this poller
 * asynchronously relays events to Redis in the background.
 *
 * <p>The poller uses a Redis consumer group ({@code outbox-pollers}) on the nudge stream ({@link
 * RedisEventPublisher#outboxNudgeStreamKey()}) with {@code XREADGROUP}. Each poller thread is an
 * independent consumer in the group, so Redis distributes entries across consumers — scaling up the
 * poller count linearly divides the work without duplication.
 *
 * <p>Writers XADD an entry per event with the tenant ID. When entries arrive, the consumer
 * dispatches per-tenant drain tasks that claim and publish events in batches until the tenant's
 * outbox is empty, then ACKs the entries.
 *
 * <p>On startup, each consumer first drains its pending entries (delivered but not yet ACKed from a
 * previous crash), then switches to reading new entries. Consumer {@code poller-0} additionally
 * runs a one-time DB discovery query to catch events written while no poller was running.
 *
 * <p>The discovery query uses a reserved sentinel tenant ID ({@code __discovery__}) that is matched
 * by a dedicated RLS policy, allowing cross-tenant visibility for the discovery SELECT only.
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
    private static final int STREAM_READ_BLOCK_MS = 5;
    private static final int STREAM_READ_COUNT = 1000;
    static final String CONSUMER_GROUP = "outbox-pollers";
    private static final String CONSUMER_PREFIX = "poller-";

    /**
     * Reserved sentinel tenant ID used for cross-tenant discovery queries. Matched by a dedicated
     * RLS policy ({@code outbox_discovery}) on the event table that allows SELECT on all rows.
     */
    static final String DISCOVERY_TENANT = "__discovery__";

    private final DSLContext dsl;
    private final RedisEventPublisher redisPublisher;
    private final int batchSize;
    private final JedisPooled signalJedis;
    private final String nudgeStreamKey;
    private final int pollerCount;

    private volatile SubjectDsarService<?> subjectDsarService;
    private volatile String encryptionKey;

    private final List<Thread> schedulerThreads = new ArrayList<>();
    private ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxPoller(
            DSLContext dsl,
            RedisEventPublisher redisPublisher,
            JedisPooled signalJedis,
            int pollerCount) {
        this(dsl, redisPublisher, DEFAULT_BATCH_SIZE, signalJedis, pollerCount);
    }

    public OutboxPoller(
            DSLContext dsl,
            RedisEventPublisher redisPublisher,
            int batchSize,
            JedisPooled signalJedis,
            int pollerCount) {
        this.dsl = dsl;
        this.redisPublisher = redisPublisher;
        this.batchSize = batchSize;
        this.signalJedis = signalJedis;
        this.nudgeStreamKey = redisPublisher.outboxNudgeStreamKey();
        this.pollerCount = Math.max(1, pollerCount);
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
     * Starts the outbox relay with {@link #pollerCount} consumer threads. Each thread is an
     * independent consumer in the {@code outbox-pollers} Redis consumer group, so entries are
     * partitioned across consumers by Redis. Per-tenant drain tasks run on virtual threads (~1 KB
     * stack) for unbounded parallelism.
     *
     * <p>On startup, each consumer drains its pending entries (from a previous crash), then
     * consumer {@code poller-0} runs a DB discovery query to catch any backlog. After that, all
     * consumers read new entries via {@code XREADGROUP BLOCK 5}.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("OutboxPoller is already running");
            return;
        }
        ensureConsumerGroup();
        workers = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < pollerCount; i++) {
            String consumerName = CONSUMER_PREFIX + i;
            boolean isPrimary = (i == 0);
            Thread t =
                    Thread.ofVirtual()
                            .name("outbox-" + consumerName)
                            .start(
                                    () -> {
                                        drainPending(consumerName);
                                        if (isPrimary) {
                                            drainBacklog();
                                        }
                                        while (running.get()
                                                && !Thread.currentThread().isInterrupted()) {
                                            readAndDispatch(consumerName);
                                        }
                                    });
            schedulerThreads.add(t);
        }
        log.info(
                "OutboxPoller started ({} consumer(s), group={}, key={})",
                pollerCount,
                CONSUMER_GROUP,
                nudgeStreamKey);
    }

    /**
     * Creates the consumer group if it does not already exist. Uses {@code MKSTREAM} so the stream
     * key is auto-created. Catches {@code BUSYGROUP} errors (group already exists) gracefully.
     */
    private void ensureConsumerGroup() {
        try {
            signalJedis.xgroupCreate(
                    nudgeStreamKey, CONSUMER_GROUP, StreamEntryID.LAST_ENTRY, true);
            log.info("Created consumer group '{}' on stream '{}'", CONSUMER_GROUP, nudgeStreamKey);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists", CONSUMER_GROUP);
            } else {
                log.warn("Failed to create consumer group — will retry on first read", e);
            }
        }
    }

    /**
     * Drains pending entries (delivered but not ACKed from a previous crash) for this consumer.
     * Uses {@code XREADGROUP} with entry ID {@code 0-0} to retrieve pending entries, processes
     * them, then ACKs.
     */
    private void drainPending(String consumerName) {
        try {
            while (running.get()) {
                var result =
                        signalJedis.xreadGroup(
                                CONSUMER_GROUP,
                                consumerName,
                                XReadGroupParams.xReadGroupParams().count(STREAM_READ_COUNT),
                                Map.of(nudgeStreamKey, new StreamEntryID()));

                if (result == null || result.isEmpty()) {
                    break;
                }

                List<StreamEntryID> ids = new ArrayList<>();
                Set<String> tenants = new HashSet<>();
                boolean hadEntries = false;
                for (var streamEntries : result) {
                    for (StreamEntry entry : streamEntries.getValue()) {
                        hadEntries = true;
                        ids.add(entry.getID());
                        String tenantId = entry.getFields().get("t");
                        if (tenantId != null) {
                            tenants.add(tenantId);
                        }
                    }
                }

                if (!hadEntries) {
                    break;
                }

                for (String tenantId : tenants) {
                    workers.submit(() -> TenantContext.runWhere(tenantId, this::drainTenant));
                }

                signalJedis.xack(nudgeStreamKey, CONSUMER_GROUP, ids.toArray(StreamEntryID[]::new));
            }
        } catch (Exception e) {
            log.warn(
                    "{}: pending entry drain failed — will proceed to new entries",
                    consumerName,
                    e);
        }
    }

    /**
     * Reads new entries from the nudge stream via {@code XREADGROUP} and dispatches per-tenant
     * drain tasks. Uses {@code BLOCK} to wait for up to {@link #STREAM_READ_BLOCK_MS} ms, naturally
     * batching signals from concurrent writers into a single round-trip. Processed entries are
     * ACKed immediately.
     */
    private void readAndDispatch(String consumerName) {
        try {
            var result =
                    signalJedis.xreadGroup(
                            CONSUMER_GROUP,
                            consumerName,
                            XReadGroupParams.xReadGroupParams()
                                    .block(STREAM_READ_BLOCK_MS)
                                    .count(STREAM_READ_COUNT),
                            Map.of(nudgeStreamKey, StreamEntryID.UNRECEIVED_ENTRY));

            if (result == null || result.isEmpty()) {
                return;
            }

            List<StreamEntryID> ids = new ArrayList<>();
            Set<String> tenants = new HashSet<>();
            for (var streamEntries : result) {
                for (StreamEntry entry : streamEntries.getValue()) {
                    ids.add(entry.getID());
                    String tenantId = entry.getFields().get("t");
                    if (tenantId != null) {
                        tenants.add(tenantId);
                    }
                }
            }

            for (String tenantId : tenants) {
                workers.submit(() -> TenantContext.runWhere(tenantId, this::drainTenant));
            }

            if (!ids.isEmpty()) {
                signalJedis.xack(nudgeStreamKey, CONSUMER_GROUP, ids.toArray(StreamEntryID[]::new));
            }
        } catch (Exception e) {
            log.warn("Outbox nudge stream read failed — will retry", e);
        }
    }

    /**
     * One-time startup drain: discovers tenants with unpublished events via DB query and dispatches
     * drain tasks. Catches events written while no poller was running or whose stream entries were
     * trimmed.
     */
    private void drainBacklog() {
        try {
            List<String> tenants = discoverTenantsWithPendingEvents();
            if (!tenants.isEmpty()) {
                log.info("Outbox startup: draining backlog for {} tenant(s)", tenants.size());
                for (String tenantId : tenants) {
                    workers.submit(() -> TenantContext.runWhere(tenantId, this::drainTenant));
                }
            }
        } catch (Exception e) {
            log.warn("Outbox startup backlog drain failed — stream will catch new events", e);
        }
    }

    /** Gracefully shuts down the poller, waiting up to 5 seconds for in-flight work to complete. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (Thread t : schedulerThreads) {
            t.interrupt();
        }
        for (Thread t : schedulerThreads) {
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        schedulerThreads.clear();
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("OutboxPoller stopped");
    }

    /**
     * Queries the event table for distinct tenant IDs with unpublished events. Uses the {@link
     * #DISCOVERY_TENANT} sentinel to activate the {@code outbox_discovery} RLS policy, which allows
     * cross-tenant SELECT visibility for this query only.
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
     * Drains all unpublished events for the current tenant, claiming and publishing in batches
     * until the outbox is empty. Each batch uses two phases to minimise the time DB row locks are
     * held:
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
    private void drainTenant() {
        try {
            String tenantId = TenantContext.get();
            String streamKey =
                    "platform".equals(tenantId)
                            ? redisPublisher.platformStreamKey()
                            : redisPublisher.shardStreamKey(tenantId);

            record ClaimedEvent(
                    Long eventId,
                    String aggregateId,
                    String aggregateType,
                    String eventType,
                    Long version,
                    String payload,
                    String eventTenantId,
                    Boolean shreddable,
                    UUID dsarId) {}

            while (running.get()) {
                // Phase 1 — Claim: short transaction to SELECT + mark published.
                // Row locks are held only for the duration of this lambda.
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
                                claimed.add(
                                        new ClaimedEvent(
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
                    break;
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
                                FieldEncryptor.decrypt(encryptedKey.get(), this.encryptionKey);
                        String ciphertext = objectMapper.readValue(payload, String.class);
                        payload = FieldEncryptor.decrypt(ciphertext, rawKey);
                    }

                    Map<String, String> fields = new HashMap<>();
                    fields.put(PlatformEvent.FIELD_EVENT_ID, String.valueOf(record.eventId()));
                    fields.put(PlatformEvent.FIELD_AGGREGATE_ID, record.aggregateId());
                    fields.put(PlatformEvent.FIELD_AGGREGATE_TYPE, record.aggregateType());
                    fields.put(PlatformEvent.FIELD_EVENT_TYPE, record.eventType());
                    fields.put(PlatformEvent.FIELD_VERSION, String.valueOf(record.version()));
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

                log.info(
                        "Outbox relayed {} events to Redis stream {}",
                        fieldsBatch.size(),
                        streamKey);
            }
        } catch (Exception e) {
            log.warn("Outbox drain failed — will retry on next nudge", e);
        }
    }
}
