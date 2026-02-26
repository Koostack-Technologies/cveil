package com.koostack.operio.core.event.models;

import com.koostack.operio.core.models.AggregateRoot;

public record PublishEventInput<T extends AggregateRoot>(
    String tenantId,
    String eventType,
    T state
) {
    public static <U extends AggregateRoot> PublishEventInput<U> from(String tenantId, String eventType, U state) {
        return new PublishEventInput<>(tenantId, eventType, state);
    }
}
