-- ============================================================================
-- V1: Unified event store schema (H2 — jOOQ codegen)
-- ============================================================================
-- Identical to platform-event unified schema. This module will be merged
-- into platform:operio-platform-event-jooq in a later phase.
-- ============================================================================

CREATE TABLE event (
    event_id        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT       NOT NULL,
    payload         CLOB,
    published       BOOLEAN      NOT NULL DEFAULT FALSE,
    shreddable      BOOLEAN      NOT NULL DEFAULT FALSE,
    dsar_id         UUID,
    UNIQUE (tenant_id, aggregate_type, aggregate_id, version)
);

CREATE INDEX idx_event_aggregate   ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant      ON event (tenant_id);
CREATE INDEX idx_event_type        ON event (event_type);
CREATE INDEX idx_event_unpublished ON event (published);
CREATE INDEX idx_event_shreddable  ON event (shreddable);
CREATE INDEX idx_event_dsar_id     ON event (dsar_id);

CREATE TABLE aggregate_snapshot (
    tenant_id       VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    snapshot        CLOB,
    PRIMARY KEY (tenant_id, aggregate_type, aggregate_id, version)
);
