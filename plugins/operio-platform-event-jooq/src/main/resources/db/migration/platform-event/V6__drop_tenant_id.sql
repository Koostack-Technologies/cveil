-- Remove redundant tenant_id column from event and aggregate_snapshot tables.
-- Tenant identity is implicit from the per-tenant schema.

-- Event table -----------------------------------------------------------------
-- H2 auto-names constraints; find and drop the unique constraint containing tenant_id
-- by dropping all unique constraints on the relevant columns, then re-adding without tenant_id.
ALTER TABLE event DROP CONSTRAINT IF EXISTS CONSTRAINT_3F;
-- Fallback: try named pattern used by some H2 versions
ALTER TABLE event DROP CONSTRAINT IF EXISTS UQ_EVENT_AGGREGATE_TYPE_AGGREGATE_ID_VERSION_TENANT_ID;
ALTER TABLE event DROP CONSTRAINT IF EXISTS EVENT_AGGREGATE_TYPE_AGGREGATE_ID_VERSION_TENANT_ID_KEY;

DROP INDEX IF EXISTS idx_event_tenant;
ALTER TABLE event DROP COLUMN tenant_id;
ALTER TABLE event ADD CONSTRAINT uq_event_aggregate_version
    UNIQUE (aggregate_type, aggregate_id, version);

-- Aggregate snapshot table ----------------------------------------------------
-- Drop primary key before dropping column (H2 requires this)
ALTER TABLE aggregate_snapshot DROP PRIMARY KEY;
ALTER TABLE aggregate_snapshot DROP COLUMN tenant_id;
ALTER TABLE aggregate_snapshot ADD PRIMARY KEY (aggregate_type, aggregate_id, version);
