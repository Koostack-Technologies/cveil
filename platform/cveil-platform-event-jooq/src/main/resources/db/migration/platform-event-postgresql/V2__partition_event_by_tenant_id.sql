-- ============================================================================
-- V2: Hash-partition the event table by tenant_id (16 partitions)
-- ============================================================================
-- PostgreSQL does not support ALTER TABLE … PARTITION BY on an existing table.
-- Strategy: rename the old table, create the new partitioned table with the
-- same structure, copy data, then drop the old table.
-- ============================================================================

-- 1. Rename the existing table out of the way
ALTER TABLE event RENAME TO event_old;

-- 2. Drop indexes on old table (they'll be recreated on the partitioned table)
DROP INDEX IF EXISTS idx_event_aggregate;
DROP INDEX IF EXISTS idx_event_tenant;
DROP INDEX IF EXISTS idx_event_type;
DROP INDEX IF EXISTS idx_event_occurred;
DROP INDEX IF EXISTS idx_event_payload;
DROP INDEX IF EXISTS idx_event_unpublished;
DROP INDEX IF EXISTS idx_event_shreddable;
DROP INDEX IF EXISTS idx_event_dsar_id;

-- 3. Create new partitioned table with composite PK (partition key must be
--    included in every unique/primary-key constraint)
CREATE TABLE event (
    event_id        BIGINT          GENERATED ALWAYS AS IDENTITY,
    tenant_id       VARCHAR(255)    NOT NULL,
    aggregate_id    VARCHAR(255)    NOT NULL,
    aggregate_type  VARCHAR(255)    NOT NULL,
    event_type      VARCHAR(255)    NOT NULL,
    occurred_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version         BIGINT          NOT NULL,
    payload         JSONB,
    published       BOOLEAN         NOT NULL DEFAULT FALSE,
    shreddable      BOOLEAN         NOT NULL DEFAULT FALSE,
    dsar_id         UUID,
    PRIMARY KEY (event_id, tenant_id),
    UNIQUE (tenant_id, aggregate_type, aggregate_id, version)
) PARTITION BY HASH (tenant_id);

-- 4. Create 16 hash partitions
CREATE TABLE event_p00 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 0);
CREATE TABLE event_p01 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 1);
CREATE TABLE event_p02 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 2);
CREATE TABLE event_p03 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 3);
CREATE TABLE event_p04 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 4);
CREATE TABLE event_p05 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 5);
CREATE TABLE event_p06 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 6);
CREATE TABLE event_p07 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 7);
CREATE TABLE event_p08 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 8);
CREATE TABLE event_p09 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 9);
CREATE TABLE event_p10 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 10);
CREATE TABLE event_p11 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 11);
CREATE TABLE event_p12 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 12);
CREATE TABLE event_p13 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 13);
CREATE TABLE event_p14 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 14);
CREATE TABLE event_p15 PARTITION OF event FOR VALUES WITH (MODULUS 16, REMAINDER 15);

-- 5. Recreate indexes (auto-propagated to every partition)
CREATE INDEX idx_event_aggregate   ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant      ON event (tenant_id);
CREATE INDEX idx_event_type        ON event (event_type);
CREATE INDEX idx_event_occurred    ON event (occurred_at);
CREATE INDEX idx_event_payload     ON event USING GIN (payload);
CREATE INDEX idx_event_unpublished ON event (published) WHERE published = FALSE;
CREATE INDEX idx_event_shreddable  ON event (shreddable) WHERE shreddable = TRUE;
CREATE INDEX idx_event_dsar_id     ON event (dsar_id)    WHERE dsar_id IS NOT NULL;

-- 6. Copy existing rows (OVERRIDING SYSTEM VALUE preserves identity values)
INSERT INTO event (event_id, tenant_id, aggregate_id, aggregate_type, event_type,
                   occurred_at, version, payload, published, shreddable, dsar_id)
    OVERRIDING SYSTEM VALUE
    SELECT event_id, tenant_id, aggregate_id, aggregate_type, event_type,
           occurred_at, version, payload, published, shreddable, dsar_id
    FROM event_old;

-- 7. Advance the identity sequence past the max existing id (minimum 1)
SELECT setval(pg_get_serial_sequence('event', 'event_id'),
              GREATEST(COALESCE((SELECT MAX(event_id) FROM event), 1), 1));

-- 8. Drop the old table
DROP TABLE event_old;
