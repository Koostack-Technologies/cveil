-- ============================================================================
-- Platform metrics telemetry table
-- ============================================================================
CREATE TABLE telemetry (
    time_bucket     TIMESTAMP WITH TIME ZONE NOT NULL,
    tenant_id       VARCHAR(255)             NOT NULL,
    metric_name     VARCHAR(100)             NOT NULL,
    dimensions      JSONB                    NOT NULL DEFAULT '{}'::jsonb,
    metric_value    DOUBLE PRECISION         NOT NULL DEFAULT 0,
    published_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time_bucket, tenant_id, metric_name, dimensions)
);

CREATE INDEX idx_platform_telemetry_tenant
    ON telemetry(tenant_id, time_bucket);

CREATE INDEX idx_platform_telemetry_dimensions
    ON telemetry USING GIN (dimensions);
