-- ============================================================================
-- V1: Platform event store schema (PostgreSQL)
-- ============================================================================
-- Uses PostgreSQL-specific features:
--   • HASH partitioning on tenant_id for workload isolation
--   • JSONB payload for efficient querying and indexing
--   • TIMESTAMPTZ for timezone-aware timestamps
--   • GIN index on JSONB payload for ad-hoc event queries
-- ============================================================================

-- --------------------------------------------------------------------------
-- Partitioned event table
-- --------------------------------------------------------------------------
-- The partition key (tenant_id) must appear in every PRIMARY KEY and UNIQUE
-- constraint so that PostgreSQL can route writes to the correct partition.
-- --------------------------------------------------------------------------

CREATE TABLE event (
    event_id        BIGINT       GENERATED ALWAYS AS IDENTITY,
    tenant_id       VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL,
    payload         JSONB,
    PRIMARY KEY (event_id, tenant_id),
    UNIQUE (aggregate_type, aggregate_id, version, tenant_id)
) PARTITION BY HASH (tenant_id);

-- --------------------------------------------------------------------------
-- Hash partitions (4 buckets)
-- --------------------------------------------------------------------------

CREATE TABLE event_p0 PARTITION OF event FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE event_p1 PARTITION OF event FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE event_p2 PARTITION OF event FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE event_p3 PARTITION OF event FOR VALUES WITH (MODULUS 4, REMAINDER 3);

-- --------------------------------------------------------------------------
-- Indexes (automatically created on every partition)
-- --------------------------------------------------------------------------

CREATE INDEX idx_event_aggregate ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant    ON event (tenant_id);
CREATE INDEX idx_event_type      ON event (event_type);
CREATE INDEX idx_event_occurred  ON event (occurred_at);
CREATE INDEX idx_event_payload   ON event USING GIN (payload);
