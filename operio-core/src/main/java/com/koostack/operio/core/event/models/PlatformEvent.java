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
}
