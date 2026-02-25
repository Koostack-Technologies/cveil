package com.koostack.operio.core.event;

import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.spi.Plugin;

public interface PlatformEventService<T> extends Plugin<T> {

    /**
     * Publish an event to the platform event bus.
     *
     * <p>Events are stored in a durable log and delivered asynchronously to
     * all subscribed {@link PlatformEventService} implementations.
     *
     * @param event the event to publish
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if publishing fails (e.g. database error)
     */
    <U> void publish(PlatformEvent<U> event);

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

}
