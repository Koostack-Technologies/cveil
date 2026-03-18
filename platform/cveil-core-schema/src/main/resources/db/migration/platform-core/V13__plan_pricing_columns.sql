-- ============================================================================
-- V13: Add numeric pricing columns to plan table
--
-- Moves pricing out of hardcoded Java constants and into the database.
-- monthly_price_cents and annual_price_cents store Stripe-compatible
-- integer amounts (in the plan's currency, assumed USD cents).
-- NULL = not billable / custom pricing (enterprise).
-- ============================================================================

ALTER TABLE platform_core.plan
    ADD COLUMN IF NOT EXISTS monthly_price_cents  INTEGER,
    ADD COLUMN IF NOT EXISTS annual_price_cents   INTEGER;

-- Populate from the pricing defined in V10 comments / original code constants
UPDATE platform_core.plan SET monthly_price_cents = 0                WHERE plan_id = 'free';
UPDATE platform_core.plan SET monthly_price_cents = 9900,  annual_price_cents = 94800  WHERE plan_id = 'pro';
UPDATE platform_core.plan SET monthly_price_cents = 49900, annual_price_cents = 478800 WHERE plan_id = 'business';
-- enterprise: NULL = custom pricing (no Stripe sync)

-- Sanity constraint: price must be non-negative when present
ALTER TABLE platform_core.plan
    ADD CONSTRAINT chk_plan_monthly_price CHECK (monthly_price_cents IS NULL OR monthly_price_cents >= 0);
ALTER TABLE platform_core.plan
    ADD CONSTRAINT chk_plan_annual_price  CHECK (annual_price_cents IS NULL OR annual_price_cents >= 0);
