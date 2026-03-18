-- ============================================================================
-- V8: Webhook reliability — circuit breaker tracking & secret rotation grace
-- ============================================================================

-- Circuit breaker: track consecutive failures to auto-disable unhealthy endpoints.
ALTER TABLE webhook_endpoint
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE webhook_endpoint
    ADD COLUMN disabled_at TIMESTAMPTZ;
ALTER TABLE webhook_endpoint
    ADD COLUMN disabled_reason VARCHAR(256);

-- Secret rotation grace period: keep the previous secret valid for a window
-- so in-flight deliveries signed with the old secret are still verifiable.
ALTER TABLE webhook_endpoint
    ADD COLUMN previous_secret VARCHAR(256);
ALTER TABLE webhook_endpoint
    ADD COLUMN previous_secret_expires_at TIMESTAMPTZ;

-- Partial index for efficient lookup of circuit-broken endpoints eligible for
-- half-open probing.
CREATE INDEX IF NOT EXISTS idx_webhook_endpoint_disabled
    ON webhook_endpoint(disabled_at)
    WHERE disabled_at IS NOT NULL;
