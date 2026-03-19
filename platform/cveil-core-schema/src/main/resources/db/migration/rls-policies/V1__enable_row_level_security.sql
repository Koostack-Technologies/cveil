-- ============================================================================
-- V1: Row-Level Security (RLS) policies for all tenant-scoped schemas
-- ============================================================================
-- Enables automatic tenant isolation at the database layer.
-- The application sets session variable 'app.tenant_id' before each query.
-- RLS policies filter rows using current_setting('app.tenant_id', true).
--
-- Schemas covered:
--   cveilconsent   — all consent administration tables
--   cveilevent     — unified event store (event, aggregate_snapshot)
--   cveilmetrics   — telemetry table
--   cveiliam       — access_token, role_assignment (tenant-scoped subset)
--
-- Tables NOT covered (platform-global, no tenant_id):
--   cveilcore.tenant, cveilcore.plan
--   cveiliam.platform_user, cveiliam.verification_token
--   cveilbilling.* (uses tenant_id but accessed by billing service)
-- ============================================================================

-- 1. Create application role (RLS applies to non-superusers only)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cveil_app') THEN
        CREATE ROLE cveil_app LOGIN;
    END IF;
END $$;

-- ============================================================================
-- 2. cveilconsent schema
-- ============================================================================
GRANT USAGE ON SCHEMA cveilconsent TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveilconsent TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA cveilconsent TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveilconsent
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveilconsent
    GRANT USAGE ON SEQUENCES TO cveil_app;

DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOR tbl IN
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = 'cveilconsent'
          AND table_type = 'BASE TABLE'
          AND table_name NOT LIKE 'flyway_%'
    LOOP
        EXECUTE format('ALTER TABLE cveilconsent.%I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE cveilconsent.%I FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON cveilconsent.%I '
            || 'USING (tenant_id = current_setting(''app.tenant_id'', true)) '
            || 'WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true))',
            tbl
        );
    END LOOP;
END $$;

-- ============================================================================
-- 3. cveilevent schema
-- ============================================================================
GRANT USAGE ON SCHEMA cveilevent TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveilevent TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA cveilevent TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveilevent
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveilevent
    GRANT USAGE ON SEQUENCES TO cveil_app;

ALTER TABLE cveilevent.event ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveilevent.event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cveilevent.event
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE cveilevent.aggregate_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveilevent.aggregate_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cveilevent.aggregate_snapshot
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 4. cveilmetrics schema
-- ============================================================================
GRANT USAGE ON SCHEMA cveilmetrics TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveilmetrics TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA cveilmetrics TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveilmetrics
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveilmetrics
    GRANT USAGE ON SEQUENCES TO cveil_app;

ALTER TABLE cveilmetrics.telemetry ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveilmetrics.telemetry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cveilmetrics.telemetry
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 5. cveiliam schema (tenant-scoped tables only)
-- ============================================================================
GRANT USAGE ON SCHEMA cveiliam TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveiliam TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA cveiliam TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveiliam
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveiliam
    GRANT USAGE ON SEQUENCES TO cveil_app;

-- RLS on access_token (has tenant_id)
ALTER TABLE cveiliam.access_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveiliam.access_token FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cveiliam.access_token
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- RLS on role_assignment (has tenant_id — NULL means platform-scope)
ALTER TABLE cveiliam.role_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveiliam.role_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cveiliam.role_assignment
    USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 6. Grant platform-global schema access (no RLS — admin operations)
-- ============================================================================
GRANT USAGE ON SCHEMA cveilcore TO cveil_app;
GRANT SELECT ON ALL TABLES IN SCHEMA cveilcore TO cveil_app;

GRANT USAGE ON SCHEMA cveilbilling TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveilbilling TO cveil_app;
