-- ============================================================================
-- V1: Row-Level Security (RLS) policies for all tenant-scoped schemas
-- ============================================================================
-- Enables automatic tenant isolation at the database layer.
-- The application sets session variable 'app.tenant_id' before each query.
-- RLS policies filter rows using current_setting('app.tenant_id', true).
--
-- Schemas covered:
--   cveil_consent  — all consent administration tables
--   cveil_event    — unified event store (event, aggregate_snapshot)
--   cveil_metrics  — telemetry table
--   platform_iam    — access_token, role_assignment (tenant-scoped subset)
--
-- Tables NOT covered (platform-global, no tenant_id):
--   platform_core.tenant, platform_core.plan
--   platform_iam.platform_user, platform_iam.verification_token
--   platform_billing.* (uses tenant_id but accessed by billing service)
-- ============================================================================

-- 1. Create application role (RLS applies to non-superusers only)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cveil_app') THEN
        CREATE ROLE cveil_app LOGIN;
    END IF;
END $$;

-- ============================================================================
-- 2. cveil_consent schema
-- ============================================================================
GRANT USAGE ON SCHEMA cveil_consent TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveil_consent TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA cveil_consent TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveil_consent
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveil_consent
    GRANT USAGE ON SEQUENCES TO cveil_app;

DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOR tbl IN
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = 'cveil_consent'
          AND table_type = 'BASE TABLE'
          AND table_name NOT LIKE 'flyway_%'
    LOOP
        EXECUTE format('ALTER TABLE cveil_consent.%I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE cveil_consent.%I FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON cveil_consent.%I '
            || 'USING (tenant_id = current_setting(''app.tenant_id'', true)) '
            || 'WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true))',
            tbl
        );
    END LOOP;
END $$;

-- ============================================================================
-- 3. cveil_event schema
-- ============================================================================
GRANT USAGE ON SCHEMA cveil_event TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveil_event TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA cveil_event TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveil_event
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveil_event
    GRANT USAGE ON SEQUENCES TO cveil_app;

ALTER TABLE cveil_event.event ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveil_event.event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cveil_event.event
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE cveil_event.aggregate_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveil_event.aggregate_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cveil_event.aggregate_snapshot
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 4. cveil_metrics schema (platform_metrics)
-- ============================================================================
GRANT USAGE ON SCHEMA platform_metrics TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform_metrics TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA platform_metrics TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_metrics
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_metrics
    GRANT USAGE ON SEQUENCES TO cveil_app;

ALTER TABLE platform_metrics.telemetry ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_metrics.telemetry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform_metrics.telemetry
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 5. platform_iam schema (tenant-scoped tables only)
-- ============================================================================
GRANT USAGE ON SCHEMA platform_iam TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform_iam TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA platform_iam TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_iam
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_iam
    GRANT USAGE ON SEQUENCES TO cveil_app;

-- RLS on access_token (has tenant_id)
ALTER TABLE platform_iam.access_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_iam.access_token FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform_iam.access_token
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- RLS on role_assignment (has tenant_id — NULL means platform-scope)
ALTER TABLE platform_iam.role_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_iam.role_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform_iam.role_assignment
    USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 6. Grant platform-global schema access (no RLS — admin operations)
-- ============================================================================
GRANT USAGE ON SCHEMA platform_core TO cveil_app;
GRANT SELECT ON ALL TABLES IN SCHEMA platform_core TO cveil_app;

GRANT USAGE ON SCHEMA platform_billing TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform_billing TO cveil_app;
