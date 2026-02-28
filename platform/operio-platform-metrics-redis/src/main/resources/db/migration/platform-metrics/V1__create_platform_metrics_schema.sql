-- ============================================================================
-- Platform metrics telemetry table
-- ============================================================================
CREATE SCHEMA IF NOT EXISTS platform_metrics;

CREATE TABLE platform_metrics.telemetry (
    time_bucket     TIMESTAMP WITH TIME ZONE NOT NULL,
    tenant_id       VARCHAR(255)             NOT NULL,
    metric_name     VARCHAR(100)             NOT NULL,
    dimensions      JSONB                    NOT NULL DEFAULT '{}'::jsonb,
    metric_value    DOUBLE PRECISION         NOT NULL DEFAULT 0,
    published_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time_bucket, tenant_id, metric_name, dimensions)
);

CREATE INDEX idx_platform_telemetry_tenant
    ON platform_metrics.telemetry(tenant_id, time_bucket);

CREATE INDEX idx_platform_telemetry_dimensions
    ON platform_metrics.telemetry USING GIN (dimensions);
