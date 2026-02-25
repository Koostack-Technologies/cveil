package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.platform.event.models.PlatformEvent;
import com.koostack.operio.plugin.event.jooq.generated.tables.records.EventRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.koostack.operio.plugin.event.jooq.generated.tables.Event.EVENT;
import static org.jooq.impl.DSL.selectDistinct;

/**
 * Polls the {@code event} table for unpublished rows and forwards them to
 * Redis.  Implements the <em>transactional outbox</em> pattern: the
 * {@code publish} call only writes to the database; this poller
 * asynchronously relays events to Redis in the background.
 *
 * <p>Work is parallelised by {@code tenant_id}: a scheduler discovers
 * tenants with unpublished events and fans each tenant out to a worker
 * thread.  Within a tenant, events are published in {@code event_id}
 * order to preserve causal ordering.
 */
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final long DEFAULT_POLL_INTERVAL_MS = 500;

    private final DSLContext dsl;
    private final RedisEventPublisher redisPublisher;
    private final int batchSize;
    private final long pollIntervalMs;

    private ScheduledExecutorService scheduler;
    private ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxPoller(DSLContext dsl, RedisEventPublisher redisPublisher) {
        this(dsl, redisPublisher, DEFAULT_BATCH_SIZE, DEFAULT_POLL_INTERVAL_MS);
    }

    public OutboxPoller(
        DSLContext dsl,
        RedisEventPublisher redisPublisher,
        int batchSize,
        long pollIntervalMs
    ) {
        this.dsl = dsl;
        this.redisPublisher = redisPublisher;
        this.batchSize = batchSize;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Starts the outbox poller with the given number of worker threads.
     */
    public void start(int workerCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("OutboxPoller is already running");
            return;
        }
        workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "outbox-worker");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-scheduler");
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

    /**
     * Gracefully shuts down the poller, waiting up to 5 seconds for
     * in-flight work to complete.
     */
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
     * Discovers distinct tenant IDs with unpublished events and submits
     * a {@link #publishForTenant} task per tenant to the worker pool.
     */
    private void discoverAndDispatch() {
        try {
            List<String> tenantIds = dsl
                .selectDistinct(EVENT.TENANT_ID)
                .from(EVENT)
                .where(EVENT.PUBLISHED.eq(false))
                .fetchInto(String.class);

            for (String tenantId : tenantIds) {
                workers.submit(() -> publishForTenant(tenantId));
            }
        } catch (Exception e) {
            log.warn("Outbox discovery cycle failed — will retry", e);
        }
    }

    /**
     * Claims and publishes a batch of unpublished events for a single tenant.
     * Uses {@code FOR UPDATE SKIP LOCKED} so concurrent workers on the same
     * tenant (across nodes) do not block each other.
     */
    private void publishForTenant(String tenantId) {
        try {
            dsl.transaction(txConfig -> {
                DSLContext txDsl = txConfig.dsl();

                List<EventRecord> batch = txDsl
                    .selectFrom(EVENT)
                    .where(EVENT.PUBLISHED.eq(false))
                    .and(EVENT.TENANT_ID.eq(tenantId))
                    .orderBy(EVENT.EVENT_ID.asc())
                    .limit(batchSize)
                    .forUpdate()
                    .skipLocked()
                    .fetchInto(EventRecord.class);

                if (batch.isEmpty()) {
                    return;
                }

                for (EventRecord record : batch) {
                    Map<String, String> fields = new HashMap<>();
                    fields.put(PlatformEvent.FIELD_EVENT_ID, String.valueOf(record.getEventId()));
                    fields.put(PlatformEvent.FIELD_TENANT_ID, record.getTenantId());
                    fields.put(PlatformEvent.FIELD_AGGREGATE_ID, record.getAggregateId());
                    fields.put(PlatformEvent.FIELD_AGGREGATE_TYPE, record.getAggregateType());
                    fields.put(PlatformEvent.FIELD_EVENT_TYPE, record.getEventType());
                    fields.put(PlatformEvent.FIELD_VERSION, String.valueOf(record.getVersion()));
                    if (record.getChanges() != null) {
                        fields.put(PlatformEvent.FIELD_CHANGES, record.getChanges());
                    }
                    redisPublisher.publishRaw(fields);
                }

                List<Long> ids = batch.stream()
                    .map(EventRecord::getEventId)
                    .toList();

                txDsl.update(EVENT)
                    .set(EVENT.PUBLISHED, true)
                    .where(EVENT.EVENT_ID.in(ids))
                    .execute();

                log.debug("Outbox relayed {} events for tenant {} to Redis", ids.size(), tenantId);
            });
        } catch (Exception e) {
            log.warn("Outbox publish failed for tenant {} — will retry", tenantId, e);
        }
    }
}
