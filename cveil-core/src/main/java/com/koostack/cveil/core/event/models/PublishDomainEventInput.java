package com.koostack.cveil.core.event.models;

import com.koostack.cveil.core.event.DomainEvent;
import com.koostack.cveil.core.models.AggregateKey;

/**
 * Input for publishing a semantic domain event to the event store.
 *
 * @param tenantId tenant owning the aggregate
 * @param eventType registered event type name
 * @param key aggregate key (id + type + tenant)
 * @param payload the semantic domain event payload
 */
public record PublishDomainEventInput(
        String tenantId, String eventType, AggregateKey key, DomainEvent payload) {
    public static PublishDomainEventInput of(
            String tenantId, String eventType, AggregateKey key, DomainEvent payload) {
        return new PublishDomainEventInput(tenantId, eventType, key, payload);
    }
}
