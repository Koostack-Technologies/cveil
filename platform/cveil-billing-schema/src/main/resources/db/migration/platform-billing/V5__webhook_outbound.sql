-- ============================================================================
-- V5: Outbound webhook endpoints and delivery log
-- ============================================================================

CREATE TABLE IF NOT EXISTS cveilbilling.webhook_endpoint (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       VARCHAR(128)    NOT NULL,
    url             VARCHAR(2048)   NOT NULL,
    secret          VARCHAR(256)    NOT NULL,
    events          TEXT            NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT true,
    description     VARCHAR(512),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_webhook_endpoint_tenant
    ON cveilbilling.webhook_endpoint(tenant_id);

CREATE TABLE IF NOT EXISTS cveilbilling.webhook_delivery_log (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id     UUID            NOT NULL REFERENCES cveilbilling.webhook_endpoint(id),
    event_type      VARCHAR(128)    NOT NULL,
    payload         JSONB           NOT NULL,
    status_code     INTEGER,
    response_body   TEXT,
    attempt         INTEGER         NOT NULL DEFAULT 1,
    delivered_at    TIMESTAMPTZ,
    next_retry_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_webhook_delivery_endpoint
    ON cveilbilling.webhook_delivery_log(endpoint_id);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_created
    ON cveilbilling.webhook_delivery_log(created_at);
