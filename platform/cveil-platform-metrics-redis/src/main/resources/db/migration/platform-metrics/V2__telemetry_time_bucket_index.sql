-- ============================================================================
-- Add time_bucket-only index for platform-wide range queries
-- The existing idx_platform_telemetry_tenant (tenant_id, time_bucket) is
-- efficient for tenant-scoped queries but cannot be used when filtering
-- by time_bucket alone (e.g. PlatformMetricsResource cross-tenant queries).
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_telemetry_time_bucket
    ON telemetry(time_bucket DESC);
