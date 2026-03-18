-- ============================================================================
-- V12: Indexes and constraints for billing reliability
-- ============================================================================

-- ── 1. CHECK constraint on plan_feature.limit_value ─────────────────────
-- NULL = unlimited when enabled; -1 = explicitly unlimited; ≥ 0 = capped.
DO $$ BEGIN
  ALTER TABLE plan_feature
      ADD CONSTRAINT chk_plan_feature_limit_value
      CHECK (limit_value IS NULL OR limit_value >= -1);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── 2. Partial index on plan(is_active) for active-plan queries ─────────
CREATE INDEX IF NOT EXISTS idx_plan_is_active
    ON plan(is_active)
    WHERE is_active = TRUE;
