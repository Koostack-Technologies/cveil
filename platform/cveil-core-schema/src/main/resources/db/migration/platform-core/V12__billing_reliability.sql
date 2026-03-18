-- ============================================================================
-- V12: Indexes and constraints for billing reliability
-- ============================================================================

-- ── 1. Index for FeatureGateChecker plan-level queries ──────────────────
-- The composite PK (plan_id, feature_key) covers exact lookups but
-- FeatureGateChecker queries all features for a plan_id.  The PK's
-- leading column already supports this, so this is a documentation
-- anchor confirming the access pattern is covered.

-- ── 2. CHECK constraint on plan_feature.limit_value ─────────────────────
-- NULL = unlimited when enabled; -1 = explicitly unlimited; ≥ 0 = capped.
ALTER TABLE platform_core.plan_feature
    ADD CONSTRAINT chk_plan_feature_limit_value
    CHECK (limit_value IS NULL OR limit_value >= -1);

-- ── 3. CHECK constraint on subscription.billing_interval ────────────────
ALTER TABLE platform_billing.subscription
    ADD CONSTRAINT chk_subscription_billing_interval
    CHECK (billing_interval IN ('monthly', 'annual'));

-- ── 4. Partial index on plan(is_active) for active-plan queries ─────────
CREATE INDEX IF NOT EXISTS idx_plan_is_active
    ON platform_core.plan(is_active)
    WHERE is_active = TRUE;
