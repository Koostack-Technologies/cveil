-- ============================================================================
-- V1: Platform event store schema (PostgreSQL)
-- ============================================================================
-- Uses PostgreSQL-specific features:
--   • JSONB payload for efficient querying and indexing
--   • TIMESTAMPTZ for timezone-aware timestamps
--   • GIN index on JSONB payload for ad-hoc event queries
-- ============================================================================

CREATE TABLE event (
    event_id        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL,
    payload         JSONB,
    UNIQUE (aggregate_type, aggregate_id, version, tenant_id)
);

-- --------------------------------------------------------------------------
-- Indexes
-- --------------------------------------------------------------------------

CREATE INDEX idx_event_aggregate ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant    ON event (tenant_id);
CREATE INDEX idx_event_type      ON event (event_type);
CREATE INDEX idx_event_occurred  ON event (occurred_at);
CREATE INDEX idx_event_payload   ON event USING GIN (payload);
