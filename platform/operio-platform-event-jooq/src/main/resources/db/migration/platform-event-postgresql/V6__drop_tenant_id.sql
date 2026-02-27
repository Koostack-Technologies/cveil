-- Remove redundant tenant_id column from event and aggregate_snapshot tables.
-- Tenant identity is implicit from the per-tenant schema.

-- Event table -----------------------------------------------------------------
DROP INDEX IF EXISTS idx_event_tenant;
ALTER TABLE event DROP CONSTRAINT event_aggregate_type_aggregate_id_version_tenant_id_key;
ALTER TABLE event DROP COLUMN tenant_id;
ALTER TABLE event ADD CONSTRAINT uq_event_aggregate_version
    UNIQUE (aggregate_type, aggregate_id, version);

-- Aggregate snapshot table ----------------------------------------------------
ALTER TABLE aggregate_snapshot DROP CONSTRAINT aggregate_snapshot_pkey;
ALTER TABLE aggregate_snapshot DROP COLUMN tenant_id;
ALTER TABLE aggregate_snapshot ADD PRIMARY KEY (aggregate_type, aggregate_id, version);
