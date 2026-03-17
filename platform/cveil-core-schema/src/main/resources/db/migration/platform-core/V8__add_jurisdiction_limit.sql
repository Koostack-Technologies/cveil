-- ============================================================================
-- V8: Add jurisdiction limit to plan definitions
--
-- Introduces a per-plan cap on the number of jurisdictions a tenant may
-- configure.  -1 means unlimited (enterprise).
-- ============================================================================

ALTER TABLE platform_core.plan
    ADD COLUMN jurisdiction_limit INTEGER NOT NULL DEFAULT 3;

-- Free / Starter  → 3 jurisdictions
UPDATE platform_core.plan SET jurisdiction_limit = 3  WHERE plan_id = 'free';

-- Growth          → 10 jurisdictions
UPDATE platform_core.plan SET jurisdiction_limit = 10 WHERE plan_id = 'pro';

-- Business        → 20 jurisdictions
UPDATE platform_core.plan SET jurisdiction_limit = 20 WHERE plan_id = 'business';

-- Enterprise      → unlimited
UPDATE platform_core.plan SET jurisdiction_limit = -1 WHERE plan_id = 'enterprise';
