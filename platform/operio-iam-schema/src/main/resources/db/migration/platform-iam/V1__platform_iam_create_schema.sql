CREATE SCHEMA IF NOT EXISTS platform_iam;

CREATE TABLE platform_iam.platform_user (
                                            platform_user_id    VARCHAR(255)  PRIMARY KEY,
                                            email               VARCHAR(255)  NOT NULL UNIQUE,
                                            full_name           VARCHAR(255),
                                            password_hash       VARCHAR(255),

                                            status              VARCHAR(50)   NOT NULL DEFAULT 'active'
                                                CHECK (status IN ('active', 'suspended', 'unverified')),

                                            last_login_at       TIMESTAMPTZ,
                                            created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                            updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE platform_iam.tenant_workspace_link (
                                                    tenant_id           VARCHAR(255)  NOT NULL REFERENCES platform_core.tenant(tenant_id) ON DELETE CASCADE,
                                                    platform_user_id    VARCHAR(255)  NOT NULL REFERENCES platform_iam.platform_user(platform_user_id) ON DELETE CASCADE,
                                                    joined_at           TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                    PRIMARY KEY (tenant_id, platform_user_id)
);

CREATE TABLE platform_iam.api_key (
                                      api_key_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                      key_hash            VARCHAR(255)  NOT NULL UNIQUE,
                                      key_prefix          VARCHAR(20)   NOT NULL,
                                      name                VARCHAR(100)  NOT NULL DEFAULT 'Default Key',

                                      tenant_id           VARCHAR(255)  NOT NULL REFERENCES platform_core.tenant(tenant_id) ON DELETE CASCADE,
                                      platform_user_id    VARCHAR(255)  NOT NULL REFERENCES platform_iam.platform_user(platform_user_id) ON DELETE CASCADE,

                                      subject             VARCHAR(255)  NOT NULL,

                                      status              VARCHAR(50)   NOT NULL DEFAULT 'active'
                                          CHECK (status IN ('active', 'revoked', 'expired')),

                                      last_used_at        TIMESTAMPTZ,
                                      expires_at          TIMESTAMPTZ,
                                      created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      created_by          VARCHAR(255),
                                      updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      updated_by          VARCHAR(255)
);

CREATE INDEX idx_api_key_tenant ON platform_iam.api_key(tenant_id);
CREATE INDEX idx_api_key_user ON platform_iam.api_key(platform_user_id);