-- ============================================================================
-- V1: Platform event store schema (SQLite)
-- ============================================================================
-- SQLite-compatible variant.  Mirrors the PostgreSQL schema as closely as
-- possible but avoids features SQLite does not support (partitioning, JSONB,
-- GENERATED ALWAYS AS IDENTITY, TIMESTAMPTZ).
-- ============================================================================

CREATE TABLE event (
    event_id        INTEGER      PRIMARY KEY AUTOINCREMENT,
    tenant_id       TEXT         NOT NULL,
    aggregate_id    TEXT         NOT NULL,
    aggregate_type  TEXT         NOT NULL,
    event_type      TEXT         NOT NULL,
    occurred_at     TEXT         NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    version         INTEGER      NOT NULL,
    payload         TEXT,
    UNIQUE (aggregate_type, aggregate_id, version)
);

-- --------------------------------------------------------------------------
-- Indexes
-- --------------------------------------------------------------------------

CREATE INDEX idx_event_aggregate ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant    ON event (tenant_id);
CREATE INDEX idx_event_type      ON event (event_type);
CREATE INDEX idx_event_occurred  ON event (occurred_at);
