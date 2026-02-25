package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.plugin.event.jooq.generated.tables.records.EventRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.jooq.impl.DSL.max;

import static com.koostack.operio.plugin.event.jooq.generated.tables.Event.EVENT;

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
    public <T> long insert(PlatformEvent<T> event, String serializedPayload) {
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
        record.setPayload(serializedPayload);
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
}
