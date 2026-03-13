package com.koostack.operio.core.metrics;

import com.koostack.operio.core.spi.Plugin;

/**
 * Unified SPI for consuming metrics from a stream and projecting them into a telemetry table.
 *
 * <p>Implementations consume from Redis metric streams and upsert rows into the appropriate
 * telemetry table:
 *
 * <ul>
 *   <li>Platform → {@code platform_metrics.telemetry}
 *   <li>Tenant → {@code t_{tenantId}_metrics.telemetry}
 * </ul>
 *
 * <p>The projection uses upsert/accumulation semantics: {@code ON CONFLICT DO UPDATE SET
 * metric_value = metric_value + EXCLUDED.metric_value}.
 *
 * @param <T> the configuration type for the backing implementation
 * @see MetricPublisherService
 * @see Metric
 */
public interface MetricProjection<T> extends Plugin<T> {

    /**
     * Starts consuming from the metric stream and projecting into the telemetry table. This method
     * is non-blocking — it spawns background consumer threads.
     */
    void start();

    /** Stops consuming and shuts down background consumer threads. */
    void stop();
}
