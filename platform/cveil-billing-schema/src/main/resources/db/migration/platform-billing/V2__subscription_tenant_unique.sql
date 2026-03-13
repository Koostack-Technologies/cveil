-- Add unique constraint on tenant_id for the single-subscription-per-tenant CQRS model.
-- Required for idempotent upserts in the billing projection handler.

ALTER TABLE platform_billing.subscription
    ADD CONSTRAINT subscription_tenant_id_unique UNIQUE (tenant_id);
