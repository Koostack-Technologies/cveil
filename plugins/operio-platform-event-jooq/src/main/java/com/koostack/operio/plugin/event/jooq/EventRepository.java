package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.plugin.event.jooq.generated.tables.records.EventRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.koostack.operio.plugin.event.jooq.generated.tables.Event.EVENT;
import static com.koostack.operio.plugin.event.jooq.generated.tables.EventType.EVENT_TYPE;
import static org.jooq.impl.DSL.max;

/**
 * Repository for persisting and querying platform events via jOOQ.
 */
public class EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);

    private final DSLContext dsl;

    public EventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Inserts a new event into the event store and returns its generated id.
     */
    public <T> long insert(PlatformEvent event, String serializedChanges) {
        EventRecord record = dsl.newRecord(EVENT);
        record.setTenantId(event.tenantId() != null ? event.tenantId().toString() : "");
        record.setAggregateId(event.aggregateId() != null ? event.aggregateId().toString() : "");
        record.setAggregateType(event.aggregateType());
        record.setEventType(event.eventType());
        record.setOccurredAt(
            event.occurredAt() != null
                ? LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)
                : LocalDateTime.now(ZoneOffset.UTC)
        );
        record.setVersion(event.version());
        record.setChanges(serializedChanges);
        record.store();
        return record.getEventId();
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
     * Fetches all stored change records (serialized JSON patches) for an
     * aggregate in version order.
     */
    public List<String> fetchChangesForAggregate(String aggregateType, String aggregateId) {
        return dsl.select(EVENT.CHANGES)
            .from(EVENT)
            .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
            .and(EVENT.AGGREGATE_ID.eq(aggregateId))
            .orderBy(EVENT.VERSION.asc())
            .fetch(EVENT.CHANGES);
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
}
