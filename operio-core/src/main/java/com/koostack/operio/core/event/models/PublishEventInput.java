package com.koostack.operio.core.event.models;

import com.koostack.operio.core.models.AggregateRoot;

import java.util.UUID;

public record PublishEventInput<T extends AggregateRoot>(
    UUID tenantId,
    String eventType,
    T state
) {
    public static <U extends AggregateRoot> PublishEventInput<U> from(UUID tenantId, String eventType, U state) {
        return new PublishEventInput<>(tenantId, eventType, state);
    }
}
