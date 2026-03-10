-- ============================================================================
-- V1: Row-Level Security (RLS) policies for all tenant-scoped schemas
-- ============================================================================
-- Enables automatic tenant isolation at the database layer.
-- The application sets session variable 'app.tenant_id' before each query.
-- RLS policies filter rows using current_setting('app.tenant_id', true).
--
-- Schemas covered:
--   operio_consent  — all consent administration tables
--   operio_event    — unified event store (event, aggregate_snapshot)
--   operio_metrics  — telemetry table
--   platform_iam    — api_key, refresh_token (tenant-scoped subset)
--
-- Tables NOT covered (platform-global, no tenant_id):
--   platform_core.tenant, platform_core.plan
--   platform_iam.platform_user, platform_iam.verification_token
--   platform_billing.* (uses tenant_id but accessed by billing service)
-- ============================================================================

-- 1. Create application role (RLS applies to non-superusers only)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operio_app') THEN
        CREATE ROLE operio_app LOGIN;
    END IF;
END $$;

-- ============================================================================
-- 2. operio_consent schema
-- ============================================================================
GRANT USAGE ON SCHEMA operio_consent TO operio_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA operio_consent TO operio_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA operio_consent TO operio_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA operio_consent
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO operio_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA operio_consent
    GRANT USAGE ON SEQUENCES TO operio_app;

DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOR tbl IN
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = 'operio_consent'
          AND table_type = 'BASE TABLE'
          AND table_name NOT LIKE 'flyway_%'
    LOOP
        EXECUTE format('ALTER TABLE operio_consent.%I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE operio_consent.%I FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON operio_consent.%I '
            || 'USING (tenant_id = current_setting(''app.tenant_id'', true)) '
            || 'WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true))',
            tbl
        );
    END LOOP;
END $$;

-- ============================================================================
-- 3. operio_event schema
-- ============================================================================
GRANT USAGE ON SCHEMA operio_event TO operio_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA operio_event TO operio_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA operio_event TO operio_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA operio_event
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO operio_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA operio_event
    GRANT USAGE ON SEQUENCES TO operio_app;

ALTER TABLE operio_event.event ENABLE ROW LEVEL SECURITY;
ALTER TABLE operio_event.event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON operio_event.event
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE operio_event.aggregate_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE operio_event.aggregate_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON operio_event.aggregate_snapshot
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 4. operio_metrics schema (platform_metrics)
-- ============================================================================
GRANT USAGE ON SCHEMA platform_metrics TO operio_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform_metrics TO operio_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA platform_metrics TO operio_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_metrics
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO operio_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_metrics
    GRANT USAGE ON SEQUENCES TO operio_app;

ALTER TABLE platform_metrics.telemetry ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_metrics.telemetry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform_metrics.telemetry
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 5. platform_iam schema (tenant-scoped tables only)
-- ============================================================================
GRANT USAGE ON SCHEMA platform_iam TO operio_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform_iam TO operio_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA platform_iam TO operio_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_iam
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO operio_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_iam
    GRANT USAGE ON SEQUENCES TO operio_app;

-- RLS on api_key (has tenant_id)
ALTER TABLE platform_iam.api_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_iam.api_key FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform_iam.api_key
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- RLS on refresh_token (has tenant_id)
ALTER TABLE platform_iam.refresh_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_iam.refresh_token FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform_iam.refresh_token
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- RLS on tenant_workspace_link (has tenant_id)
ALTER TABLE platform_iam.tenant_workspace_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_iam.tenant_workspace_link FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform_iam.tenant_workspace_link
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 6. Grant platform-global schema access (no RLS — admin operations)
-- ============================================================================
GRANT USAGE ON SCHEMA platform_core TO operio_app;
GRANT SELECT ON ALL TABLES IN SCHEMA platform_core TO operio_app;

GRANT USAGE ON SCHEMA platform_billing TO operio_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform_billing TO operio_app;
