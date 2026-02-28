-- Refresh tokens: Operio-issued opaque tokens wrapping IdP refresh tokens.
-- Stored with SHA-256 hash for lookup; the underlying IdP refresh token
-- is kept in idp_refresh_token for delegation to Keycloak/external IdPs.

CREATE TABLE platform_iam.refresh_token (
    refresh_token_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash          VARCHAR(255)  NOT NULL UNIQUE,
    platform_user_id    VARCHAR(255)  NOT NULL REFERENCES platform_iam.platform_user(platform_user_id) ON DELETE CASCADE,
    tenant_id           VARCHAR(255)  NOT NULL REFERENCES platform_core.tenant(tenant_id) ON DELETE CASCADE,
    idp_refresh_token   TEXT,
    expires_at          TIMESTAMPTZ   NOT NULL,
    revoked             BOOLEAN       NOT NULL DEFAULT FALSE,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_token_user   ON platform_iam.refresh_token(platform_user_id);
CREATE INDEX idx_refresh_token_tenant ON platform_iam.refresh_token(tenant_id);

-- Verification tokens: email verification and password reset tokens.
-- Stored as SHA-256 hash; raw token is sent to the user via email.

CREATE TABLE platform_iam.verification_token (
    token_hash          VARCHAR(255)  PRIMARY KEY,
    platform_user_id    VARCHAR(255)  NOT NULL REFERENCES platform_iam.platform_user(platform_user_id) ON DELETE CASCADE,
    token_type          VARCHAR(50)   NOT NULL CHECK (token_type IN ('email_verification', 'password_reset')),
    expires_at          TIMESTAMPTZ   NOT NULL,
    used                BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_verification_token_user ON platform_iam.verification_token(platform_user_id);

-- Link platform_user to external IdP identity.
-- Allows tracking which IdP issued the user's identity and their
-- IdP-specific subject identifier (Keycloak user ID, Auth0 sub, etc.).

ALTER TABLE platform_iam.platform_user ADD COLUMN idp_provider VARCHAR(100);
ALTER TABLE platform_iam.platform_user ADD COLUMN idp_subject  VARCHAR(255);

CREATE UNIQUE INDEX idx_platform_user_idp ON platform_iam.platform_user(idp_provider, idp_subject)
    WHERE idp_provider IS NOT NULL AND idp_subject IS NOT NULL;
