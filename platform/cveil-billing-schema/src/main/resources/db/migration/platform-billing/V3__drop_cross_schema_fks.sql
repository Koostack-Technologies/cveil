-- Drop cross-schema FK constraints from billing projection tables.
-- In a CQRS architecture, projection tables are independent read models;
-- cross-schema FKs cause race conditions when parallel consumer groups
-- process events at different speeds.

ALTER TABLE platform_billing.billing_profile
    DROP CONSTRAINT IF EXISTS billing_profile_tenant_id_fkey;

ALTER TABLE platform_billing.subscription
    DROP CONSTRAINT IF EXISTS subscription_tenant_id_fkey;

ALTER TABLE platform_billing.daily_usage_rollup
    DROP CONSTRAINT IF EXISTS daily_usage_rollup_tenant_id_fkey;
