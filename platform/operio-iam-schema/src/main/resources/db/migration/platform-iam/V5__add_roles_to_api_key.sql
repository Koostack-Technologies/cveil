-- Add roles column for RBAC on API keys.
-- Subject already exists from V1; roles stores comma-separated role names.

ALTER TABLE platform_iam.api_key
    ADD COLUMN IF NOT EXISTS roles VARCHAR(500);
