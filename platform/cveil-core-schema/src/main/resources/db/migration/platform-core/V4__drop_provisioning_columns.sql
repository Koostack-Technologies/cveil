-- ============================================================================
-- V4: Drop per-tenant schema column (unified tenant architecture)
-- ============================================================================
-- The per-tenant schema provisioning pipeline is eliminated. Tenants no
-- longer get individual Postgres schemas. All tenant data lives in shared
-- schemas with RLS isolation. Redis ACL columns are retained because
-- per-tenant Redis stream/ACL isolation is still in effect.
-- ============================================================================

ALTER TABLE platform_core.tenant DROP COLUMN IF EXISTS schema_prefix;
