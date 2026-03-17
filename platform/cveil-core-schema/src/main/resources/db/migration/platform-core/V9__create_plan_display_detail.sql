-- ============================================================================
-- V9: Create plan_display_detail table for UI pricing page
--
-- Stores presentation-layer fields for each plan (price label, feature
-- descriptions, CTA text, etc.) that are consumed by the public pricing
-- page.  Separated from the operational plan table so that marketing
-- copy can evolve independently of billing/quota logic.
-- ============================================================================

-- ── 1. Correct operational limits to match published pricing ────────────

-- Free / Starter: 1,000 managed subjects (was 10,000)
UPDATE platform_core.plan SET subject_limit = 1000, updated_at = CURRENT_TIMESTAMP
WHERE plan_id = 'free';

-- Growth: 7 jurisdictions (was 10)
UPDATE platform_core.plan SET jurisdiction_limit = 7, updated_at = CURRENT_TIMESTAMP
WHERE plan_id = 'pro';

-- Business: 20M API requests (was unlimited), unlimited governance
UPDATE platform_core.plan SET eval_limit = 20000000, jurisdiction_limit = -1, updated_at = CURRENT_TIMESTAMP
WHERE plan_id = 'business';

-- ── 2. Create display-detail table ─────────────────────────────────────

CREATE TABLE platform_core.plan_display_detail (
    plan_id         VARCHAR(50)   PRIMARY KEY REFERENCES platform_core.plan(plan_id),
    price           VARCHAR(50)   NOT NULL,
    period          VARCHAR(20)   NOT NULL DEFAULT '',
    overage         VARCHAR(100)  NOT NULL DEFAULT '',
    governance      VARCHAR(200)  NOT NULL DEFAULT '',
    support         VARCHAR(200)  NOT NULL DEFAULT '',
    residency       VARCHAR(200)  NOT NULL DEFAULT '',
    cta             VARCHAR(100)  NOT NULL DEFAULT 'Get Started',
    highlighted     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ── 3. Seed display detail for each plan tier ──────────────────────────

INSERT INTO platform_core.plan_display_detail
    (plan_id, price, period, overage, governance, support, residency, cta, highlighted)
VALUES
    ('free',
     'Free', '', 'Hard stop',
     '3 jurisdictions, 5 regulations',
     'Documentation', 'Shared US / EU',
     'Get Started Free', FALSE),

    ('pro',
     '$49', '/mo', '$0.002 / request',
     '7 jurisdictions, 20 regulations',
     '72-hour email', 'Shared US / EU',
     'Start Free Trial', FALSE),

    ('business',
     '$249', '/mo', '$0.002 / request',
     'Unlimited', '24-hour priority email',
     'Dedicated region',
     'Start Free Trial', TRUE),

    ('enterprise',
     'Custom', '', '$0.002 / request',
     'Unlimited', 'Dedicated Slack channel · 1-hour response',
     'Custom / on-prem',
     'Contact Sales', FALSE);
