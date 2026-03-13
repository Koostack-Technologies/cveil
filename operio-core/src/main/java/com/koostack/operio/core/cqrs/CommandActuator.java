package com.koostack.operio.core.cqrs;

import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;

/**
 * Extension of {@link Actuator} for command-side operations that produce side effects.
 *
 * <p>In addition to the inherited {@link #execute(Object)} method, a command actuator exposes both
 * an {@link EventService} for publishing domain events and a {@link MetricPublisherService} for
 * emitting telemetry metrics.
 *
 * @param <I> the command input type
 * @param <O> the result type produced by the actuator
 */
public interface CommandActuator<I, O> extends Actuator<I, O> {

    /**
     * Returns the event publisher used by this command actuator.
     *
     * @return the event service instance
     */
    EventService<?> eventPublisher();

    /**
     * Returns the metrics publisher used by this command actuator.
     *
     * @return the metric publisher service instance
     */
    MetricPublisherService<?> metricsPublisher();
}
