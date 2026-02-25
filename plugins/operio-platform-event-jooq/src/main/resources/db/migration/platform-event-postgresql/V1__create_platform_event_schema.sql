-- ============================================================================
-- V1: Platform event store schema (PostgreSQL)
-- ============================================================================
-- Uses PostgreSQL-specific features:
--   • JSONB payload for efficient querying and indexing
--   • TIMESTAMPTZ for timezone-aware timestamps
--   • GIN index on JSONB payload for ad-hoc event queries
-- ============================================================================


-- --------------------------------------------------------------------------
-- Indexes
-- --------------------------------------------------------------------------

CREATE INDEX idx_event_aggregate ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant    ON event (tenant_id);
CREATE INDEX idx_event_type      ON event (event_type);
CREATE INDEX idx_event_occurred  ON event (occurred_at);
CREATE INDEX idx_event_payload   ON event USING GIN (payload);
