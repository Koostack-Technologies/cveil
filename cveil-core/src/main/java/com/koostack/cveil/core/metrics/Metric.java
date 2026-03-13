package com.koostack.cveil.core.metrics;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A generic telemetry metric to be published to a metric stream and projected into a telemetry
 * table.
 *
 * <h2>Field Semantics</h2>
 *
 * <ul>
 *   <li>{@code metricName} — identifies the metric (e.g. {@code "api_evaluations"}, {@code
 *       "proxy_latency_ms"})
 *   <li>{@code metricValue} — the numeric measurement
 *   <li>{@code metricType} — determines upsert strategy (COUNTER accumulates, GAUGE replaces)
 *   <li>{@code tenantId} — scopes the metric; {@code null} for platform-level metrics (uses {@code
 *       "SYSTEM"} in the telemetry table)
 *   <li>{@code dimensions} — arbitrary tags for grouping and filtering (e.g. {@code {"region":
 *       "us-east", "status": "200"}})
 *   <li>{@code publishedAt} — the exact instant the metric was emitted; set automatically if not
 *       provided
 * </ul>
 *
 * <h2>Redis Stream Field Names</h2>
 *
 * <p>When serialized to a Redis stream, the following field names are used:
 *
 * <ul>
 *   <li>{@value #FIELD_METRIC_NAME}
 *   <li>{@value #FIELD_METRIC_VALUE}
 *   <li>{@value #FIELD_METRIC_TYPE}
 *   <li>{@value #FIELD_TENANT_ID}
 *   <li>{@value #FIELD_DIMENSIONS}
 *   <li>{@value #FIELD_PUBLISHED_AT}
 * </ul>
 */
public final class Metric {

    // Redis stream field names
    public static final String FIELD_METRIC_NAME = "metricName";
    public static final String FIELD_METRIC_VALUE = "metricValue";
    public static final String FIELD_METRIC_TYPE = "metricType";
    public static final String FIELD_TENANT_ID = "tenantId";
    public static final String FIELD_DIMENSIONS = "dimensions";
    public static final String FIELD_PUBLISHED_AT = "publishedAt";

    private final String metricName;
    private final double metricValue;
    private final MetricType metricType;
    private final String tenantId;
    private final Map<String, String> dimensions;
    private final Instant publishedAt;

    private Metric(Builder builder) {
        this.metricName = Objects.requireNonNull(builder.metricName, "metricName must not be null");
        this.metricValue = builder.metricValue;
        this.metricType = builder.metricType != null ? builder.metricType : MetricType.COUNTER;
        this.tenantId = builder.tenantId;
        this.dimensions = builder.dimensions != null ? Map.copyOf(builder.dimensions) : Map.of();
        this.publishedAt = builder.publishedAt != null ? builder.publishedAt : Instant.now();
    }

    public String getMetricName() {
        return metricName;
    }

    public double getMetricValue() {
        return metricValue;
    }

    public MetricType getMetricType() {
        return metricType;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Map<String, String> getDimensions() {
        return dimensions;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    /**
     * Returns {@code true} if this metric targets the platform scope (i.e. {@code tenantId} is
     * {@code null}).
     */
    public boolean isPlatformScoped() {
        return tenantId == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String metricName;
        private double metricValue;
        private MetricType metricType;
        private String tenantId;
        private Map<String, String> dimensions;
        private Instant publishedAt;

        private Builder() {}

        public Builder metricName(String metricName) {
            this.metricName = metricName;
            return this;
        }

        public Builder metricValue(double metricValue) {
            this.metricValue = metricValue;
            return this;
        }

        public Builder metricType(MetricType metricType) {
            this.metricType = metricType;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder dimensions(Map<String, String> dims) {
            this.dimensions = dims;
            return this;
        }

        public Builder publishedAt(Instant publishedAt) {
            this.publishedAt = publishedAt;
            return this;
        }

        public Metric build() {
            return new Metric(this);
        }
    }

    @Override
    public String toString() {
        return "Metric{"
                + "metricName='"
                + metricName
                + '\''
                + ", metricValue="
                + metricValue
                + ", metricType="
                + metricType
                + ", tenantId='"
                + tenantId
                + '\''
                + ", dimensions="
                + dimensions
                + ", publishedAt="
                + publishedAt
                + '}';
    }
}
