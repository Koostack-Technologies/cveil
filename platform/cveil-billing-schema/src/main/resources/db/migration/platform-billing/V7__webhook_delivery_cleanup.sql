-- V7: Webhook delivery log retention and cleanup support.
-- Adds a composite index for efficient cleanup queries and documents the
-- recommended retention policy (30 days by default).

-- Composite index for cleanup: find old deliveries efficiently
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_cleanup
    ON cveilbilling.webhook_delivery_log(endpoint_id, created_at);

-- Partial index for fast lookup of pending retries
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_pending_retry
    ON cveilbilling.webhook_delivery_log(next_retry_at)
    WHERE next_retry_at IS NOT NULL AND status_code IS NULL;

-- NOTE: To clean up old delivery logs, run periodically:
--   DELETE FROM cveilbilling.webhook_delivery_log
--   WHERE created_at < now() - INTERVAL '30 days';
-- This should be invoked by a scheduled task (e.g., Quarkus @Scheduled).
