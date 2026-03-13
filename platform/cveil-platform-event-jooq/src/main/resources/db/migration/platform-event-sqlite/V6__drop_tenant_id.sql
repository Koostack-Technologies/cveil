-- Remove redundant tenant_id column from event and aggregate_snapshot tables.
-- Tenant identity is implicit from the per-tenant schema.
-- SQLite requires table recreation to drop columns from constraints.

-- Event table -----------------------------------------------------------------
DROP INDEX IF EXISTS idx_event_tenant;

CREATE TABLE event_new (
    event_id        INTEGER      PRIMARY KEY AUTOINCREMENT,
    aggregate_id    TEXT         NOT NULL,
    aggregate_type  TEXT         NOT NULL,
    event_type      TEXT         NOT NULL,
    occurred_at     TEXT         NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    version         INTEGER      NOT NULL,
    changes         TEXT,
    published       INTEGER      NOT NULL DEFAULT 0,
    UNIQUE (aggregate_type, aggregate_id, version)
);

INSERT INTO event_new (event_id, aggregate_id, aggregate_type, event_type, occurred_at, version, changes, published)
    SELECT event_id, aggregate_id, aggregate_type, event_type, occurred_at, version, changes, published
    FROM event;

DROP TABLE event;
ALTER TABLE event_new RENAME TO event;

CREATE INDEX idx_event_aggregate  ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_type       ON event (event_type);
CREATE INDEX idx_event_occurred   ON event (occurred_at);
CREATE INDEX idx_event_unpublished ON event (published);

-- Aggregate snapshot table ----------------------------------------------------
CREATE TABLE aggregate_snapshot_new (
    aggregate_id    TEXT NOT NULL,
    aggregate_type  TEXT NOT NULL,
    version         INTEGER NOT NULL,
    snapshot        TEXT,
    PRIMARY KEY (aggregate_type, aggregate_id, version)
);

INSERT INTO aggregate_snapshot_new (aggregate_id, aggregate_type, version, snapshot)
    SELECT aggregate_id, aggregate_type, version, snapshot
    FROM aggregate_snapshot;

DROP TABLE aggregate_snapshot;
ALTER TABLE aggregate_snapshot_new RENAME TO aggregate_snapshot;
