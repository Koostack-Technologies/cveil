package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
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
 * <p>Replaces the now-deprecated {@link TenantEventService} and {@link PlatformEventService}
 * interfaces, which had identical method signatures.
 *
 * @param <T> the configuration type for the backing implementation
 * @see TenantEventService
 * @see PlatformEventService
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
     * No-op. The {@code event_type} catalog table has been removed. Retained for backward
     * compatibility with existing callers.
     *
     * @deprecated This method does nothing and will be removed in a future release.
     */
    @Deprecated(forRemoval = true)
    default <U> void registerEventType(RegisterEventTypeInput<U> input) {}
}
