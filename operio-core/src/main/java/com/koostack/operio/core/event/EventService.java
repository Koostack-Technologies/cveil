package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.core.spi.Plugin;

/**
 * Unified event service SPI — publishes and replays domain events regardless of whether they target
 * a tenant-scoped or platform-scoped event store.
 *
 * <p>Routing is determined by the presence of a {@code tenantId} on the {@link
 * PublishDomainEventInput} and {@link AggregateKey}:
 *
 * <ul>
 *   <li>Non-null tenant ID → tenant-scoped event store
 *   <li>Null tenant ID → platform-scoped event store
 * </ul>
 *
 * <p>Replaces the legacy {@code TenantEventService} and {@code PlatformEventService} interfaces,
 * which had identical method signatures.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface EventService<T> extends Plugin<T> {

    /**
     * Publish a semantic domain event to the event store.
     *
     * @param input carries tenant id, event type, aggregate key, and the semantic domain event
     *     payload
     * @throws com.koostack.operio.core.exception.OperioServiceException if publishing fails
     */
    void publishEvent(PublishDomainEventInput input);

    /**
     * Retrieve the current version number for a given aggregate.
     *
     * @param key the aggregate key identifying the aggregate
     * @return the current version number, or 0 if no events exist for this aggregate
     */
    Long getAggregateVersion(AggregateKey key);

    /**
     * Hydrate the current state of an aggregate by replaying all stored domain events, returning
     * the result as a typed object.
     *
     * <p>Default implementation throws {@link UnsupportedOperationException}. Implementations that
     * support aggregate hydration (e.g. tenant event services) override this method.
     *
     * @param key the aggregate key identifying the aggregate
     * @param type the aggregate class to hydrate into
     * @param <T> the aggregate type
     * @return the fully hydrated aggregate, or {@code null} if no events exist
     */
    default <T extends AggregateRoot> T hydrateAggregate(AggregateKey key, Class<T> type) {
        throw new UnsupportedOperationException(
                "hydrateAggregate is not supported by this EventService implementation");
    }

}
