-- ============================================================================
-- V6: Add external payment processor columns to plan table
--
-- Maps local plans to their Stripe Product and Price identifiers, enabling
-- bidirectional sync between the plan catalog and the payment processor.
-- ============================================================================

ALTER TABLE plan
    ADD COLUMN IF NOT EXISTS stripe_product_id  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS stripe_price_id    VARCHAR(255);
