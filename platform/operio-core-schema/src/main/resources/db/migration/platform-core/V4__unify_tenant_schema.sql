-- ============================================================================
-- V4: Align platform_core.tenant with the admin-service jOOQ model.
--
-- The imperative JooqPlatformAdminService uses jOOQ-generated code that
-- expects columns: schema_name, eval_limit_monthly, subject_limit,
-- burst_limit_per_min, created_by, updated_by.
--
-- The CQRS projection (CoreProjectionHandler) previously used
-- schema_prefix.  After this migration both sides reference schema_name.
-- ============================================================================

-- Rename schema_prefix → schema_name to match the jOOQ-generated Tenant POJO
ALTER TABLE platform_core.tenant RENAME COLUMN schema_prefix TO schema_name;

-- Rate-limit columns (with plan-appropriate defaults)
ALTER TABLE platform_core.tenant
    ADD COLUMN IF NOT EXISTS eval_limit_monthly  BIGINT NOT NULL DEFAULT 10000,
    ADD COLUMN IF NOT EXISTS subject_limit       BIGINT NOT NULL DEFAULT 1000,
    ADD COLUMN IF NOT EXISTS burst_limit_per_min INT    NOT NULL DEFAULT 100;

-- Audit columns (nullable — not all code paths supply an actor)
ALTER TABLE platform_core.tenant
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Allow NULL display_name (the admin-service schema permits it)
ALTER TABLE platform_core.tenant ALTER COLUMN display_name DROP NOT NULL;
