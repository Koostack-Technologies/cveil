-- ============================================================================
-- V7: Update plan catalog to 4-tier model
--
-- Replaces the placeholder 3-tier seed (free/pro/enterprise) with the
-- production tier definitions:
--   free       → Developer / Starter (free)
--   pro        → Growth (monthly subscription) — keeps 'pro' plan_id for
--                backwards compatibility with existing tenant FK references
--   business   → Business / Professional (new)
--   enterprise → Enterprise (custom pricing)
-- ============================================================================

-- 1. Update existing 'free' tier (Developer / Starter)
UPDATE platform_core.plan SET
    display_name  = 'Starter',
    description   = 'For individual developers and early-stage startups validating the API and integrating basic consent flows.',
    eval_limit    = 100000,
    subject_limit = 10000,
    burst_limit   = 60,
    is_public     = TRUE,
    sort_order    = 0,
    updated_at    = CURRENT_TIMESTAMP
WHERE plan_id = 'free';

-- 2. Update existing 'pro' tier → Growth
UPDATE platform_core.plan SET
    display_name  = 'Growth',
    description   = 'For growing businesses that need to prove GDPR/CCPA compliance and handle moderate traffic.',
    eval_limit    = 2000000,
    subject_limit = 100000,
    burst_limit   = 300,
    is_public     = TRUE,
    sort_order    = 1,
    updated_at    = CURRENT_TIMESTAMP
WHERE plan_id = 'pro';

-- 3. Insert new 'business' tier (Business / Professional)
INSERT INTO platform_core.plan (plan_id, display_name, description, eval_limit, subject_limit, burst_limit, is_public, is_active, sort_order)
VALUES (
    'business',
    'Business',
    'For mid-market companies with complex compliance needs across multiple regions and high throughput requirements.',
    -1,
    1000000,
    1000,
    TRUE,
    TRUE,
    2
);

-- 4. Update existing 'enterprise' tier
UPDATE platform_core.plan SET
    display_name  = 'Enterprise',
    description   = 'For large enterprises requiring massive scale, strict data isolation, or on-premise/VPC deployments.',
    eval_limit    = -1,
    subject_limit = -1,
    burst_limit   = -1,
    is_public     = TRUE,
    sort_order    = 3,
    updated_at    = CURRENT_TIMESTAMP
WHERE plan_id = 'enterprise';
