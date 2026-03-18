-- ============================================================================
-- V1: Access Token schema for platform IAM
-- ============================================================================
-- Stores personal access tokens (PATs) that provide long-lived API access
-- tied to a tenant. Tokens use a cveil_ prefix, are SHA-256 hashed for
-- storage, and carry a snapshot of the creator's Keycloak roles.
-- ============================================================================

CREATE TABLE access_token (
    access_token_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash          VARCHAR(255)  NOT NULL UNIQUE,
    token_prefix        VARCHAR(20)   NOT NULL,
    name                VARCHAR(100)  NOT NULL,
    tenant_id           VARCHAR(255)  NOT NULL,
    roles               VARCHAR(500)  NOT NULL,
    status              VARCHAR(50)   NOT NULL DEFAULT 'active'
                            CHECK (status IN ('active', 'revoked', 'expired')),
    expires_at          TIMESTAMPTZ   NOT NULL,
    created_by          VARCHAR(255)  NOT NULL,
    last_used_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_access_token_tenant ON access_token(tenant_id);
CREATE INDEX idx_access_token_created_by ON access_token(created_by);
CREATE INDEX idx_access_token_status ON access_token(tenant_id, status);
