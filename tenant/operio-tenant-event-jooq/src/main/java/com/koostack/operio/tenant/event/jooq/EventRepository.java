package com.koostack.operio.tenant.event.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fge.jsonpatch.JsonPatch;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.tenant.event.jooq.generated.tables.records.EventRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.koostack.operio.tenant.event.jooq.generated.tables.AggregateSnapshot.AGGREGATE_SNAPSHOT;
import static com.koostack.operio.tenant.event.jooq.generated.tables.Event.EVENT;
import static com.koostack.operio.tenant.event.jooq.generated.tables.EventType.EVENT_TYPE;
import static org.jooq.impl.DSL.max;

/**
 * Repository for persisting and querying tenant events via jOOQ.
 */
public class EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final DSLContext dsl;

    public EventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Inserts a new event into the event store and returns its generated id.
     */
    public <T> long insert(TenantEvent event, String serializedChanges) {
        return dsl.insertInto(EVENT)
            .set(EVENT.TENANT_ID, event.tenantId())
            .set(EVENT.AGGREGATE_ID, event.aggregateId() != null ? event.aggregateId().toString() : "")
            .set(EVENT.AGGREGATE_TYPE, event.aggregateType())
            .set(EVENT.EVENT_TYPE, event.eventType())
            .set(EVENT.OCCURRED_AT,
                event.occurredAt() != null
                    ? LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)
                    : LocalDateTime.now(ZoneOffset.UTC)
            )
            .set(EVENT.VERSION, event.version())
            .set(EVENT.CHANGES, serializedChanges)
            .returningResult(EVENT.EVENT_ID)
            .fetchOne()
            .get(EVENT.EVENT_ID);
    }

    /**
     * Returns the current maximum version for a given aggregate, or 0 if none exists.
     */
    public Long getAggregateVersion(String aggregateType, String aggregateId) {
        Long version = dsl.select(max(EVENT.VERSION))
            .from(EVENT)
            .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
            .and(EVENT.AGGREGATE_ID.eq(aggregateId))
            .fetchOneInto(Long.class);
        return version != null ? version : 0L;
    }

    /**
     * Fetches all events for an aggregate, ordered by version.
     */
    public List<TenantEvent> fetchChangesForAggregate(String aggregateType, String aggregateId) {
        return dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
            .and(EVENT.AGGREGATE_ID.eq(aggregateId))
            .orderBy(EVENT.VERSION.asc())
            .fetch(this::toTenantEvent);
    }

    /**
     * Fetches events for an aggregate up to and including the given version.
     */
    public List<TenantEvent> fetchChangesUpToVersion(String aggregateType, String aggregateId, Long version) {
        return dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
            .and(EVENT.AGGREGATE_ID.eq(aggregateId))
            .and(EVENT.VERSION.le(version))
            .orderBy(EVENT.VERSION.asc())
            .fetch(this::toTenantEvent);
    }

    /**
     * Persists an aggregate snapshot (upsert — replaces existing snapshot at same key).
     */
    public void insertSnapshot(String aggregateType, String aggregateId, String tenantId, Long version, String serializedSnapshot) {
        dsl.insertInto(AGGREGATE_SNAPSHOT)
            .set(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE, aggregateType)
            .set(AGGREGATE_SNAPSHOT.AGGREGATE_ID, aggregateId)
            .set(AGGREGATE_SNAPSHOT.TENANT_ID, tenantId)
            .set(AGGREGATE_SNAPSHOT.VERSION, version)
            .set(AGGREGATE_SNAPSHOT.SNAPSHOT, serializedSnapshot)
            .onConflictDoNothing()
            .execute();
    }

    /**
     * Fetches the latest snapshot for an aggregate, returning the version and
     * serialized state. Empty if no snapshot exists.
     */
    public Optional<SnapshotRecord> fetchLatestSnapshot(String aggregateType, String aggregateId) {
        return dsl.select(AGGREGATE_SNAPSHOT.VERSION, AGGREGATE_SNAPSHOT.SNAPSHOT)
            .from(AGGREGATE_SNAPSHOT)
            .where(AGGREGATE_SNAPSHOT.AGGREGATE_TYPE.eq(aggregateType))
            .and(AGGREGATE_SNAPSHOT.AGGREGATE_ID.eq(aggregateId))
            .orderBy(AGGREGATE_SNAPSHOT.VERSION.desc())
            .limit(1)
            .fetchOptional(record -> new SnapshotRecord(
                record.get(AGGREGATE_SNAPSHOT.VERSION),
                record.get(AGGREGATE_SNAPSHOT.SNAPSHOT)
            ));
    }

    /**
     * Fetches events for an aggregate after a given version, ordered by version ascending.
     */
    public List<TenantEvent> fetchChangesAfterVersion(String aggregateType, String aggregateId, Long version) {
        return dsl.selectFrom(EVENT)
            .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
            .and(EVENT.AGGREGATE_ID.eq(aggregateId))
            .and(EVENT.VERSION.gt(version))
            .orderBy(EVENT.VERSION.asc())
            .fetch(this::toTenantEvent);
    }

    /**
     * Registers an event type (idempotent — conflicts are ignored).
     */
    public <U> void insertEventType(RegisterEventTypeInput<U> input) {
        dsl.insertInto(EVENT_TYPE)
            .set(EVENT_TYPE.NAME, input.name())
            .set(EVENT_TYPE.DISPLAY_NAME, input.displayName())
            .set(EVENT_TYPE.FQN, input.eventClass().getName())
            .onConflictDoNothing()
            .execute();
    }

    /**
     * Loads all event type names from the database.
     */
    public Set<String> fetchAllEventTypeNames() {
        return dsl.select(EVENT_TYPE.NAME)
            .from(EVENT_TYPE)
            .fetchSet(EVENT_TYPE.NAME);
    }

    public record SnapshotRecord(Long version, String snapshot) {}

    private TenantEvent toTenantEvent(EventRecord record) {
        JsonPatch changes = null;
        try {
            if (record.getChanges() != null) {
                changes = objectMapper.readValue(record.getChanges(), JsonPatch.class);
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize changes for event {}", record.getEventId(), e);
        }
        return new TenantEvent(
            record.getEventId(),
            record.getTenantId(),
            record.getAggregateId() != null ? UUID.fromString(record.getAggregateId()) : null,
            record.getAggregateType(),
            record.getEventType(),
            record.getOccurredAt() != null ? record.getOccurredAt().toInstant(ZoneOffset.UTC) : null,
            record.getVersion(),
            changes
        );
    }
}
