-- ============================================================================
-- V10: Pricing revamp — update plan limits and add new columns
--
-- Updates the 4-tier plan catalog to production pricing:
--   free       → 10K API requests / 500 subjects / 30 burst
--   pro        → 500K API requests / 50K subjects / 200 burst  ($99/mo)
--   business   → 5M API requests / 500K subjects / 500 burst   ($499/mo)
--   enterprise → unlimited (custom pricing)
--
-- Adds member_limit, audit_retention_days, and stripe_annual_price_id
-- columns to support team member caps, audit log retention policies, and
-- annual billing.
-- ============================================================================

-- ── 1. Add new columns ──────────────────────────────────────────────────

ALTER TABLE plan
    ADD COLUMN IF NOT EXISTS member_limit           INTEGER NOT NULL DEFAULT -1,
    ADD COLUMN IF NOT EXISTS audit_retention_days    INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN IF NOT EXISTS stripe_annual_price_id  VARCHAR(255);

-- ── 2. Update plan operational limits ───────────────────────────────────

-- Free / Starter: 10K API requests, 500 subjects, 30 burst/min, 3 jurisdictions, 5 members
UPDATE plan SET
    eval_limit          = 10000,
    subject_limit       = 500,
    burst_limit         = 30,
    jurisdiction_limit  = 3,
    member_limit        = 5,
    audit_retention_days = 30,
    updated_at          = CURRENT_TIMESTAMP
WHERE plan_id = 'free';

-- Growth: 500K API requests, 50K subjects, 200 burst/min, 7 jurisdictions, 20 members
UPDATE plan SET
    eval_limit          = 500000,
    subject_limit       = 50000,
    burst_limit         = 200,
    jurisdiction_limit  = 7,
    member_limit        = 20,
    audit_retention_days = 90,
    updated_at          = CURRENT_TIMESTAMP
WHERE plan_id = 'pro';

-- Business: 5M API requests, 500K subjects, 500 burst/min, unlimited jurisdictions, 100 members
UPDATE plan SET
    eval_limit          = 5000000,
    subject_limit       = 500000,
    burst_limit         = 500,
    jurisdiction_limit  = -1,
    member_limit        = 100,
    audit_retention_days = 365,
    updated_at          = CURRENT_TIMESTAMP
WHERE plan_id = 'business';

-- Enterprise: unlimited everything
UPDATE plan SET
    eval_limit          = -1,
    subject_limit       = -1,
    burst_limit         = -1,
    jurisdiction_limit  = -1,
    member_limit        = -1,
    audit_retention_days = -1,
    updated_at          = CURRENT_TIMESTAMP
WHERE plan_id = 'enterprise';

-- ── 3. Update display detail with revised pricing ───────────────────────

UPDATE plan_display_detail SET
    price       = 'Free',
    period      = '',
    overage     = 'Hard stop',
    governance  = '3 jurisdictions, 5 regulations',
    support     = 'Community',
    residency   = 'Shared US / EU',
    cta         = 'Get Started Free',
    highlighted = FALSE,
    updated_at  = CURRENT_TIMESTAMP
WHERE plan_id = 'free';

UPDATE plan_display_detail SET
    price       = '$99',
    period      = '/mo',
    overage     = '$0.001 / request',
    governance  = '7 jurisdictions, 20 regulations',
    support     = '48-hour email',
    residency   = 'Shared US / EU',
    cta         = 'Start Free Trial',
    highlighted = FALSE,
    updated_at  = CURRENT_TIMESTAMP
WHERE plan_id = 'pro';

UPDATE plan_display_detail SET
    price       = '$499',
    period      = '/mo',
    overage     = '$0.0005 / request',
    governance  = 'Unlimited',
    support     = '24-hour priority email',
    residency   = 'Dedicated region',
    cta         = 'Start Free Trial',
    highlighted = TRUE,
    updated_at  = CURRENT_TIMESTAMP
WHERE plan_id = 'business';

UPDATE plan_display_detail SET
    price       = 'Custom',
    period      = '',
    overage     = 'Volume discount',
    governance  = 'Unlimited',
    support     = 'Dedicated Slack · 1-hour response',
    residency   = 'Custom / on-prem',
    cta         = 'Contact Sales',
    highlighted = FALSE,
    updated_at  = CURRENT_TIMESTAMP
WHERE plan_id = 'enterprise';
