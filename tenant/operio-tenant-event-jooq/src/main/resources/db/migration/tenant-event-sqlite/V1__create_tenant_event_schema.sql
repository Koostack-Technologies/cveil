-- ============================================================================
-- V1: Tenant event store schema (SQLite)
-- ============================================================================
-- SQLite-compatible variant. Mirrors the PostgreSQL schema as closely as
-- possible but avoids features SQLite does not support.
-- ============================================================================

CREATE TABLE event (
    event_id        INTEGER      PRIMARY KEY AUTOINCREMENT,
    tenant_id       TEXT         NOT NULL,
    aggregate_id    TEXT         NOT NULL,
    aggregate_type  TEXT         NOT NULL,
    event_type      TEXT         NOT NULL,
    occurred_at     TEXT         NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    version         INTEGER      NOT NULL,
    changes         TEXT,
    published       INTEGER      NOT NULL DEFAULT 0,
    UNIQUE (aggregate_type, aggregate_id, version, tenant_id)
);

-- --------------------------------------------------------------------------
-- Indexes
-- --------------------------------------------------------------------------

CREATE INDEX idx_event_aggregate   ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant      ON event (tenant_id);
CREATE INDEX idx_event_type        ON event (event_type);
CREATE INDEX idx_event_occurred    ON event (occurred_at);
CREATE INDEX idx_event_unpublished ON event (published);

CREATE TABLE event_type (
    event_type_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    fqn             TEXT NOT NULL
);

CREATE TABLE aggregate_snapshot (
    aggregate_id    TEXT NOT NULL,
    aggregate_type  TEXT NOT NULL,
    tenant_id       TEXT NOT NULL,
    version         INTEGER NOT NULL,
    snapshot        TEXT,
    PRIMARY KEY (aggregate_type, aggregate_id, tenant_id, version)
);
