-- Remove foreign key constraints from consent projection (read-model) tables.
--
-- These tables are populated asynchronously by the ConsentAdminProjectionHandler
-- via the event outbox → Redis stream pipeline. Events for different aggregate
-- types can arrive at the projection handler in any order (cross-shard parallelism),
-- which causes FK violations when a child row is projected before its parent.
--
-- In a CQRS/event-sourced architecture, the read model is eventually consistent by
-- design. Referential integrity is guaranteed by the command side (event store), not
-- the projection tables. Removing these FKs lets the projection handler process
-- events in any order without failures.
--
-- The UNIQUE and NOT NULL constraints remain in place — they protect against
-- duplicate projections and missing data, which are genuine bugs rather than
-- ordering artifacts.

-- consent_ledger → policy, subject
ALTER TABLE consent_ledger DROP CONSTRAINT IF EXISTS consent_ledger_policy_uuid_fkey;
ALTER TABLE consent_ledger DROP CONSTRAINT IF EXISTS consent_ledger_tenant_id_subject_id_fkey;

-- policy → purpose, field, category
ALTER TABLE policy DROP CONSTRAINT IF EXISTS policy_purpose_uuid_fkey;
ALTER TABLE policy DROP CONSTRAINT IF EXISTS policy_field_uuid_fkey;
ALTER TABLE policy DROP CONSTRAINT IF EXISTS policy_category_uuid_fkey;

-- purpose_regulation → purpose, regulation
ALTER TABLE purpose_regulation DROP CONSTRAINT IF EXISTS purpose_regulation_purpose_uuid_fkey;
ALTER TABLE purpose_regulation DROP CONSTRAINT IF EXISTS purpose_regulation_regulation_uuid_fkey;

-- regulation_jurisdiction → regulation, jurisdiction
ALTER TABLE regulation_jurisdiction DROP CONSTRAINT IF EXISTS regulation_jurisdiction_regulation_uuid_fkey;
ALTER TABLE regulation_jurisdiction DROP CONSTRAINT IF EXISTS regulation_jurisdiction_jurisdiction_uuid_fkey;

-- field_category → field, category
ALTER TABLE field_category DROP CONSTRAINT IF EXISTS field_category_field_uuid_fkey;
ALTER TABLE field_category DROP CONSTRAINT IF EXISTS field_category_category_uuid_fkey;

-- field → entity
ALTER TABLE field DROP CONSTRAINT IF EXISTS field_entity_uuid_fkey;
