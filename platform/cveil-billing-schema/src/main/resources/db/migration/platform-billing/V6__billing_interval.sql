-- ============================================================================
-- V6: Add billing_interval column to subscription table
--
-- Supports monthly and annual billing intervals. Existing subscriptions
-- default to 'monthly' for backwards compatibility.
-- ============================================================================

ALTER TABLE subscription
    ADD COLUMN IF NOT EXISTS billing_interval VARCHAR(20) NOT NULL DEFAULT 'monthly';

COMMENT ON COLUMN subscription.billing_interval
    IS 'Billing interval: monthly or annual';
