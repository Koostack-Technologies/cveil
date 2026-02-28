package com.koostack.operio.core.metrics;

/**
 * Metric aggregation type.
 *
 * <p>Determines how duplicate metrics for the same
 * {@code (time_bucket, tenant_id, metric_name, dimensions)} key are merged
 * during projection.
 */
public enum MetricType {

    /**
     * A monotonically increasing counter (e.g. request count, bytes transferred).
     * Values are accumulated via summation on upsert.
     */
    COUNTER,

    /**
     * A point-in-time measurement (e.g. CPU %, active connections).
     * The latest value replaces the previous one on upsert.
     */
    GAUGE
}
