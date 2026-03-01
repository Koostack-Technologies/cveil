-- Drop cross-schema FK constraints from IAM projection tables.
-- In a CQRS architecture, projection consumer groups process events
-- independently; FKs to other projection schemas cause race conditions.

ALTER TABLE platform_iam.api_key
    DROP CONSTRAINT IF EXISTS api_key_tenant_id_fkey;

ALTER TABLE platform_iam.tenant_workspace_link
    DROP CONSTRAINT IF EXISTS tenant_workspace_link_tenant_id_fkey;

ALTER TABLE platform_iam.refresh_token
    DROP CONSTRAINT IF EXISTS refresh_token_tenant_id_fkey;
