package com.koostack.operio.plugin.event.jooq;

import static com.koostack.operio.plugin.event.jooq.generated.tables.AggregateSnapshot.AGGREGATE_SNAPSHOT;
import static com.koostack.operio.plugin.event.jooq.generated.tables.Event.EVENT;
import static org.jooq.impl.DSL.max;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.event.jooq.generated.tables.records.EventRecord;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified repository for persisting and querying events via jOOQ.
 *
 * <p>Handles both platform and tenant events in a single {@code event} table. The {@code tenant_id}
 * column is set from {@link TenantContext} on every insert. Row-Level Security (RLS) filters reads
 * automatically.
 */
public class EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private final DSLContext dsl;

    public EventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Inserts a new event (non-shreddable) and returns its generated id. */
    public long insert(PlatformEvent event) {
        return insert(event, false, null);
    }

    /** Inserts a new event with optional shreddable/DSAR metadata and returns its generated id. */
    public long insert(PlatformEvent event, boolean shreddable, UUID dsarId) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            tenantId = "platform";
        }
        return dsl.insertInto(EVENT)
                .set(EVENT.TENANT_ID, tenantId)
                .set(
                        EVENT.AGGREGATE_ID,
                        event.aggregateId() != null ? event.aggregateId().toString() : "")
                .set(EVENT.AGGREGATE_TYPE, event.aggregateType())
                .set(EVENT.EVENT_TYPE, event.eventType())
                .set(
                        EVENT.OCCURRED_AT,
                        event.occurredAt() != null
                                ? LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)
                                : LocalDateTime.now(ZoneOffset.UTC))
                .set(EVENT.VERSION, event.version())
                .set(EVENT.PAYLOAD, event.payload())
                .set(EVENT.SHREDDABLE, shreddable)
                .set(EVENT.DSAR_ID, dsarId)
                .returningResult(EVENT.EVENT_ID)
                .fetchOne()
                .get(EVENT.EVENT_ID);
    }

    /** Returns the current maximum version for a given aggregate, or 0 if none exists. */
    public Long getAggregateVersion(String aggregateType, String aggregateId) {
        String tenantId = TenantContext.get();
        var query =
                dsl.select(max(EVENT.VERSION))
                        .from(EVENT)
                        .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
                        .and(EVENT.AGGREGATE_ID.eq(aggregateId));
        if (tenantId != null) {
            query = query.and(EVENT.TENANT_ID.eq(tenantId));
        }
        Long version = query.fetchOneInto(Long.class);
        return version != null ? version : 0L;
    }

    /** Fetches all events for an aggregate, ordered by version. */
    public List<PlatformEvent> fetchChangesForAggregate(String aggregateType, String aggregateId) {
        String tenantId = TenantContext.get();
        var query =
                dsl.selectFrom(EVENT)
                        .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
                        .and(EVENT.AGGREGATE_ID.eq(aggregateId));
        if (tenantId != null) {
            query = query.and(EVENT.TENANT_ID.eq(tenantId));
        }
        return query.orderBy(EVENT.VERSION.asc()).fetch(this::toPlatformEvent);
    }

    /** Fetches events for an aggregate up to and including the given version. */
    public List<PlatformEvent> fetchChangesUpToVersion(
            String aggregateType, String aggregateId, Long version) {
        String tenantId = TenantContext.get();
        var query =
                dsl.selectFrom(EVENT)
                        .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
                        .and(EVENT.AGGREGATE_ID.eq(aggregateId))
                        .and(EVENT.VERSION.le(version));
        if (tenantId != null) {
            query = query.and(EVENT.TENANT_ID.eq(tenantId));
        }
        return query.orderBy(EVENT.VERSION.asc()).fetch(this::toPlatformEvent);
    }

    /** Persists an aggregate snapshot (upsert — replaces existing snapshot at same key). */
    public void insertSnapshot(
            String aggregateType, String aggregateId, Long version, String serializedSnapshot) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            tenantId = "platform";
        }
        dsl.insertInto(AGGREGATE_SNAPSHOT)
                .set(AGGREGATE_SNAPSHOT.TENANT_ID, tenantId)
                .set(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE, aggregateType)
                .set(AGGREGATE_SNAPSHOT.AGGREGATE_ID, aggregateId)
                .set(AGGREGATE_SNAPSHOT.VERSION, version)
                .set(AGGREGATE_SNAPSHOT.SNAPSHOT, serializedSnapshot)
                .onConflictDoNothing()
                .execute();
    }

    /**
     * Fetches the latest snapshot for an aggregate. Empty if no snapshot exists. RLS filters by
     * tenant automatically.
     */
    public Optional<SnapshotRecord> fetchLatestSnapshot(String aggregateType, String aggregateId) {
        return dsl.select(AGGREGATE_SNAPSHOT.VERSION, AGGREGATE_SNAPSHOT.SNAPSHOT)
                .from(AGGREGATE_SNAPSHOT)
                .where(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE.eq(aggregateType))
                .and(AGGREGATE_SNAPSHOT.AGGREGATE_ID.eq(aggregateId))
                .orderBy(AGGREGATE_SNAPSHOT.VERSION.desc())
                .limit(1)
                .fetchOptional(
                        record ->
                                new SnapshotRecord(
                                        record.get(AGGREGATE_SNAPSHOT.VERSION),
                                        record.get(AGGREGATE_SNAPSHOT.SNAPSHOT)));
    }

    /** Fetches events for an aggregate after a given version, ordered by version ascending. */
    public List<PlatformEvent> fetchChangesAfterVersion(
            String aggregateType, String aggregateId, Long version) {
        return dsl.selectFrom(EVENT)
                .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
                .and(EVENT.AGGREGATE_ID.eq(aggregateId))
                .and(EVENT.VERSION.gt(version))
                .orderBy(EVENT.VERSION.asc())
                .fetch(this::toPlatformEvent);
    }

    public record SnapshotRecord(Long version, String snapshot) {}

    private PlatformEvent toPlatformEvent(EventRecord record) {
        return new PlatformEvent(
                record.getEventId(),
                record.getAggregateId() != null ? UUID.fromString(record.getAggregateId()) : null,
                record.getAggregateType(),
                record.getEventType(),
                record.getOccurredAt() != null
                        ? record.getOccurredAt().toInstant(ZoneOffset.UTC)
                        : null,
                record.getVersion(),
                record.getPayload());
    }
}
