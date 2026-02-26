-- ============================================================================
-- V1: Tenant event store schema (PostgreSQL)
-- ============================================================================
-- Uses PostgreSQL-specific features:
--   • JSONB payload for efficient querying and indexing
--   • TIMESTAMPTZ for timezone-aware timestamps
--   • GIN index on JSONB changes for ad-hoc event queries
-- ============================================================================

CREATE TABLE event (
    event_id        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL,
    changes         JSONB,
    published       BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (aggregate_type, aggregate_id, version, tenant_id)
);

-- --------------------------------------------------------------------------
-- Indexes
-- --------------------------------------------------------------------------

CREATE INDEX idx_event_aggregate   ON event (aggregate_type, aggregate_id);
CREATE INDEX idx_event_tenant      ON event (tenant_id);
CREATE INDEX idx_event_type        ON event (event_type);
CREATE INDEX idx_event_occurred    ON event (occurred_at);
CREATE INDEX idx_event_changes     ON event USING GIN (changes);
CREATE INDEX idx_event_unpublished ON event (published) WHERE published = FALSE;

CREATE TABLE event_type (
    event_type_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    display_name    VARCHAR(255) NOT NULL,
    fqn             VARCHAR(512) NOT NULL
);

CREATE TABLE aggregate_snapshot (
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    version         BIGINT NOT NULL,
    snapshot        JSONB,
    PRIMARY KEY (aggregate_type, aggregate_id, tenant_id, version)
);
