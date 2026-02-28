package com.koostack.operio.core.metrics;

import com.koostack.operio.core.spi.Plugin;

/**
 * Unified SPI for publishing telemetry metrics to a stream.
 *
 * <p>Routing is determined by the {@code tenantId} on the
 * {@link Metric} being published:
 * <ul>
 *   <li>Non-null tenant ID → tenant-scoped metric stream
 *       ({@code operio:metrics:tenant:{tenantId}})</li>
 *   <li>Null tenant ID → platform-scoped metric stream
 *       ({@code operio:metrics:platform})</li>
 * </ul>
 *
 * <p>Publishing is at-least-once: the implementation publishes directly
 * to a Redis stream with async retry via virtual threads.
 *
 * @param <T> the configuration type for the backing implementation
 * @see Metric
 * @see MetricProjection
 */
public interface MetricPublisherService<T> extends Plugin<T> {

    /**
     * Publishes a metric to the appropriate stream.
     *
     * @param metric the metric to publish
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if publishing fails after retries
     */
    void publish(Metric metric);
}
