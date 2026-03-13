package com.koostack.cveil.core.event.models;

import java.time.Instant;
import java.util.UUID;

public record PlatformEvent(
        Long eventId,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        Instant occurredAt,
        Long version,
        String payload) {
    public static final String FIELD_EVENT_ID = "eventId";
    public static final String FIELD_AGGREGATE_ID = "aggregateId";
    public static final String FIELD_AGGREGATE_TYPE = "aggregateType";
    public static final String FIELD_EVENT_TYPE = "eventType";
    public static final String FIELD_VERSION = "version";
    public static final String FIELD_PAYLOAD = "payload";
}
