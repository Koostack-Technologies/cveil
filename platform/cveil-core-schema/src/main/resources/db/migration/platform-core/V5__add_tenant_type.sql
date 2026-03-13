-- ============================================================================
-- V5: Add tenant_type column to distinguish user and org tenants
-- ============================================================================
-- Both individual users and organizations are tenants. This column makes the
-- distinction explicit at the platform level so queries can filter by type
-- without joining into the IAM schema.
-- ============================================================================

ALTER TABLE platform_core.tenant
    ADD COLUMN tenant_type VARCHAR(50) NOT NULL DEFAULT 'user'
        CHECK (tenant_type IN ('user', 'org'));

CREATE INDEX idx_tenant_type ON platform_core.tenant(tenant_type);
