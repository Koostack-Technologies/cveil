package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.spi.Plugin;

/**
 * Unified event service SPI — publishes and replays domain events
 * regardless of whether they target a tenant-scoped or platform-scoped
 * event store.
 *
 * <p>Routing is determined by the presence of a {@code tenantId} on the
 * {@link PublishDomainEventInput} and {@link AggregateKey}:
 * <ul>
 *   <li>Non-null tenant ID → tenant-scoped event store</li>
 *   <li>Null tenant ID → platform-scoped event store</li>
 * </ul>
 *
 * <p>Replaces the now-deprecated {@link TenantEventService} and
 * {@link PlatformEventService} interfaces, which had identical method
 * signatures.
 *
 * @param <T> the configuration type for the backing implementation
 * @see TenantEventService
 * @see PlatformEventService
 */
public interface EventService<T> extends Plugin<T> {

    /**
     * Publish a semantic domain event to the event store.
     *
     * @param input carries tenant id, event type, aggregate key, and the
     *              semantic domain event payload
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if publishing fails
     */
    void publishEvent(PublishDomainEventInput input);

    /**
     * Retrieve the current version number for a given aggregate.
     *
     * @param key the aggregate key identifying the aggregate
     * @return the current version number, or 0 if no events exist for this
     *         aggregate
     */
    Long getAggregateVersion(AggregateKey key);

    /**
     * Hydrate the current state of an aggregate by replaying all stored
     * event payloads, returning the result as a typed object.
     *
     * @param key  the aggregate key identifying the aggregate
     * @param type the class to deserialize the hydrated state into
     * @return the hydrated aggregate, or an empty instance if no events exist
     */
    <U> U getAggregate(AggregateKey key, Class<U> type);

    /**
     * Register an event type so that the store knows how to serialize
     * and deserialize events of this type.
     *
     * @param input carries the event type name, display name, and class
     * @param <U>   the event payload type
     */
    <U> void registerEventType(RegisterEventTypeInput<U> input);

}
