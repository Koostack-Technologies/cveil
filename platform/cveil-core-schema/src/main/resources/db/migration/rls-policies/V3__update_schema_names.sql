-- ============================================================================
-- V3: Full RLS setup for renamed schemas
-- ============================================================================
-- Schema renames (handled by application startup code):
--   cveil_consent  → cveilconsent
--   cveil_event    → cveilevent
--   platform_metrics → cveilmetrics
--   platform_iam   → cveiliam
--   platform_core  → cveilcore
--   platform_billing → cveilbilling
--
-- tenant table moved: cveilcore → cveiliam
--
-- On existing databases V1/V2 already created policies under old schema names;
-- those policies moved with the schema rename. This migration re-creates
-- everything under new names (DROP IF EXISTS keeps it idempotent).
--
-- On fresh databases V1/V2 are skipped via Flyway baseline, so this is
-- the first (and only) RLS migration to run.
-- ============================================================================

-- 0. Ensure application role exists
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cveil_app') THEN
        CREATE ROLE cveil_app LOGIN;
    END IF;
END $$;

-- ============================================================================
-- 1. cveilconsent schema (was cveil_consent)
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
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON cveilconsent.%I', tbl);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON cveilconsent.%I '
            || 'USING (tenant_id = current_setting(''app.tenant_id'', true)) '
            || 'WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true))',
            tbl
        );
    END LOOP;
END $$;

-- ============================================================================
-- 2. cveilevent schema (was cveil_event)
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
DROP POLICY IF EXISTS tenant_isolation ON cveilevent.event;
CREATE POLICY tenant_isolation ON cveilevent.event
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE cveilevent.aggregate_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveilevent.aggregate_snapshot FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON cveilevent.aggregate_snapshot;
CREATE POLICY tenant_isolation ON cveilevent.aggregate_snapshot
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- Outbox poller discovery policy (was V2)
DROP POLICY IF EXISTS outbox_discovery ON cveilevent.event;
CREATE POLICY outbox_discovery ON cveilevent.event
    FOR SELECT
    USING (current_setting('app.tenant_id', true) = '__discovery__');

-- ============================================================================
-- 3. cveilmetrics schema (was platform_metrics)
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
DROP POLICY IF EXISTS tenant_isolation ON cveilmetrics.telemetry;
CREATE POLICY tenant_isolation ON cveilmetrics.telemetry
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 4. cveiliam schema (was platform_iam, now also has tenant table)
-- ============================================================================
GRANT USAGE ON SCHEMA cveiliam TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveiliam TO cveil_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA cveiliam TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveiliam
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cveil_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cveiliam
    GRANT USAGE ON SEQUENCES TO cveil_app;

ALTER TABLE cveiliam.access_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveiliam.access_token FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON cveiliam.access_token;
CREATE POLICY tenant_isolation ON cveiliam.access_token
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE cveiliam.role_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE cveiliam.role_assignment FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON cveiliam.role_assignment;
CREATE POLICY tenant_isolation ON cveiliam.role_assignment
    USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true));

-- ============================================================================
-- 5. cveilcore schema (was platform_core, minus tenant table)
-- ============================================================================
GRANT USAGE ON SCHEMA cveilcore TO cveil_app;
GRANT SELECT ON ALL TABLES IN SCHEMA cveilcore TO cveil_app;

-- ============================================================================
-- 6. cveilbilling schema (was platform_billing)
-- ============================================================================
GRANT USAGE ON SCHEMA cveilbilling TO cveil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cveilbilling TO cveil_app;
