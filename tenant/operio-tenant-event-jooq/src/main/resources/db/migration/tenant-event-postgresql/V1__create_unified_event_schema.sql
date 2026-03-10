-- ============================================================================
-- V1: Unified event store schema (PostgreSQL)
-- ============================================================================
-- Identical to platform-event-postgresql unified schema. This module will be
-- merged into platform:operio-platform-event-jooq in a later phase.
-- ============================================================================

CREATE TABLE event (
    event_id        BIGINT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
    UNIQUE (tenant_id, aggregate_type, aggregate_id, version)
);

CREATE INDEX idx_event_aggregate   ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant      ON event (tenant_id);
CREATE INDEX idx_event_type        ON event (event_type);
CREATE INDEX idx_event_occurred    ON event (occurred_at);
CREATE INDEX idx_event_payload     ON event USING GIN (payload);
CREATE INDEX idx_event_unpublished ON event (published) WHERE published = FALSE;
CREATE INDEX idx_event_shreddable  ON event (shreddable) WHERE shreddable = TRUE;
CREATE INDEX idx_event_dsar_id     ON event (dsar_id)    WHERE dsar_id IS NOT NULL;

CREATE TABLE aggregate_snapshot (
    tenant_id       VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    snapshot        JSONB,
    PRIMARY KEY (tenant_id, aggregate_type, aggregate_id, version)
);
