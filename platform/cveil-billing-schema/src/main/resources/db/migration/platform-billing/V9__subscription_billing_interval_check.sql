-- ============================================================================
-- V9: CHECK constraint on subscription.billing_interval
-- ============================================================================

DO $$ BEGIN
  ALTER TABLE subscription
      ADD CONSTRAINT chk_subscription_billing_interval
      CHECK (billing_interval IN ('monthly', 'annual'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
