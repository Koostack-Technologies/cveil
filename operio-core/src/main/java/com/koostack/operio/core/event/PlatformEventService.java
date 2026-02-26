package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.spi.Plugin;

public interface PlatformEventService<T> extends Plugin<T> {

    /**
     * Publish a semantic domain event to the platform event store.
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

    <U> void registerEventType(RegisterEventTypeInput<U> input);

}
