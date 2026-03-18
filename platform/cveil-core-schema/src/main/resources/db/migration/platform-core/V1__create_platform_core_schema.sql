CREATE TABLE tenant (
                                      tenant_id           VARCHAR(255)  PRIMARY KEY,
                                      display_name        VARCHAR(500)  NOT NULL,
                                      plan                VARCHAR(50)   NOT NULL DEFAULT 'free',

                                      status              VARCHAR(50)   NOT NULL DEFAULT 'active'
                                          CHECK (status IN ('active', 'suspended', 'deleted', 'past_due')),

    -- Used by the backend to set search_path (e.g., 'tenant_acme_authz')
                                      schema_prefix       VARCHAR(255)  NOT NULL UNIQUE,

                                      redis_acl_user      VARCHAR(255),
                                      redis_acl_pass_hash VARCHAR(255),
                                      redis_acl_password  VARCHAR(2048), -- Widened for AES-256-GCM

                                      created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);