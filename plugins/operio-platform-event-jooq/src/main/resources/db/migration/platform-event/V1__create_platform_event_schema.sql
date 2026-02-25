-- Platform event store schema

CREATE TABLE event (
    event_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    occurred_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT NOT NULL,
    payload         CLOB,
    UNIQUE (aggregate_type, aggregate_id, version, tenant_id)
);

CREATE INDEX idx_event_aggregate
    ON event(aggregate_type, aggregate_id);

CREATE INDEX idx_event_tenant
    ON event(tenant_id);
