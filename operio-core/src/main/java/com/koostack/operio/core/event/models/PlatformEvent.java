package com.koostack.operio.core.event.models;

import java.time.Instant;
import java.util.UUID;

public record PlatformEvent<T>(
    Long eventId,
    UUID tenantId,
    UUID aggregateId,
    String aggregateType,
    String eventType,
    Instant occurredAt,
    Long version,
    T payload
) {
    public static final String FIELD_EVENT_ID       = "eventId";
    public static final String FIELD_TENANT_ID      = "tenantId";
    public static final String FIELD_AGGREGATE_ID   = "aggregateId";
    public static final String FIELD_AGGREGATE_TYPE = "aggregateType";
    public static final String FIELD_EVENT_TYPE     = "eventType";
    public static final String FIELD_VERSION        = "version";
    public static final String FIELD_PAYLOAD        = "payload";
}
