-- Add environment column for multi-environment API keys (DEV, TEST, PROD).
-- Add key_id for domain-level lookups (UUID assigned by IamCommandService).
-- Make platform_user_id and subject nullable for system-generated keys.

ALTER TABLE platform_iam.api_key
    ADD COLUMN key_id VARCHAR(255) UNIQUE;

ALTER TABLE platform_iam.api_key
    ADD COLUMN environment VARCHAR(10) NOT NULL DEFAULT 'PROD'
        CHECK (environment IN ('DEV', 'TEST', 'PROD'));

ALTER TABLE platform_iam.api_key
    ALTER COLUMN platform_user_id DROP NOT NULL;

ALTER TABLE platform_iam.api_key
    ALTER COLUMN subject DROP NOT NULL;

CREATE INDEX idx_api_key_env ON platform_iam.api_key(tenant_id, environment);
