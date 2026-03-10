-- ============================================================================
-- V6: Add tenant_id to platform_user for primary organization binding
-- ============================================================================
-- In the unified tenancy model, each user is associated with a primary
-- organization (tenant). This column enables quick tenant lookups without
-- joining tenant_workspace_link.
-- ============================================================================

ALTER TABLE platform_iam.platform_user
    ADD COLUMN tenant_id VARCHAR(255);
