package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.event.models.PublishEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.core.spi.Plugin;

public interface PlatformEventService<T> extends Plugin<T> {

    /**
     * Publish an event to the platform event bus.
     *
     * <p>The current aggregate state is hydrated by replaying stored patches,
     * diffed against the provided updated state, and the resulting JSON Patch
     * is persisted as a new event.
     *
     * @param input the publish input carrying tenant id, event type, and updated state
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if publishing fails (e.g. database error)
     */
    <U extends AggregateRoot> void publish(PublishEventInput<U> input);

    /**
     * Retrieve the current version number for a given aggregate.
     *
     * <p>Used for optimistic concurrency control when publishing events. The
     * version is incremented with each new event for the same aggregate.
     *
     * @param aggregateType the type of the aggregate (e.g. "User", "Order")
     * @param aggregateId   the unique identifier of the aggregate
     * @return the current version number, or 0 if no events exist for this
     *         aggregate
     */
    Long getAggregateVersion(String aggregateType, String aggregateId);

    /**
     * Hydrate the current state of an aggregate by replaying all stored
     * change patches, returning the result as a typed object.
     *
     * @param aggregateType the type of the aggregate
     * @param aggregateId   the unique identifier of the aggregate
     * @param type          the class to deserialize the hydrated state into
     * @return the hydrated aggregate, or an empty instance if no events exist
     */
    <U> U getAggregate(String aggregateType, String aggregateId, Class<U> type);

    <U> void registerEventType(RegisterEventTypeInput<U> input);

}
