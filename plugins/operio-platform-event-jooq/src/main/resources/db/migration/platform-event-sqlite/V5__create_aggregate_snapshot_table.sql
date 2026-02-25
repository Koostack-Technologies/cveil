-- Aggregate snapshot table for materialised state caching
CREATE TABLE aggregate_snapshot (
    aggregate_id    TEXT NOT NULL,
    aggregate_type  TEXT NOT NULL,
    tenant_id       TEXT NOT NULL,
    snapshot        TEXT,
    PRIMARY KEY (aggregate_type, aggregate_id)
);
