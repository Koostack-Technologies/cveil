package com.koostack.operio.core.cqrs;

import com.koostack.operio.core.metrics.MetricPublisherService;

/**
 * Extension of {@link Actuator} for query-side (read-only) operations.
 *
 * <p>Query actuators expose a {@link MetricPublisherService} for emitting telemetry metrics but do
 * not carry an event publisher, since queries should not produce domain events.
 *
 * @param <I> the query input type
 * @param <O> the result type produced by the actuator
 */
public interface QueryActuator<I, O> extends Actuator<I, O> {

    /**
     * Returns the metrics publisher used by this query actuator.
     *
     * @return the metric publisher service instance
     */
    MetricPublisherService<?> metricsPublisher();
}

