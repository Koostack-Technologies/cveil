-- Aggregate snapshot table for materialised state caching
CREATE TABLE aggregate_snapshot (
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    snapshot        CLOB,
    PRIMARY KEY (aggregate_type, aggregate_id)
);
