-- ============================================================================
-- V1: Platform Schema — shared across all tenants. NOT tenant-routed.
-- ============================================================================
-- This schema lives in the default Postgres search_path (public or a
-- dedicated operio_platform schema set at connection time).
--
-- Compatible with H2 (build-time jOOQ codegen) and PostgreSQL (production).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- tenant: each row represents a tenant on the Operio platform
-- ----------------------------------------------------------------------------
CREATE TABLE tenant (
    tenant_id           VARCHAR(255)  PRIMARY KEY,
    display_name        VARCHAR(500),
    plan                VARCHAR(50)   NOT NULL DEFAULT 'free',
    status              VARCHAR(50)   NOT NULL DEFAULT 'active',
    schema_name         VARCHAR(255)  NOT NULL UNIQUE,
    redis_acl_user      VARCHAR(255),
    redis_acl_pass_hash VARCHAR(255),
    eval_limit_monthly  BIGINT        NOT NULL DEFAULT 10000,
    subject_limit       BIGINT        NOT NULL DEFAULT 1000,
    burst_limit_per_min INT           NOT NULL DEFAULT 100,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(255),
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(255)
);

-- ----------------------------------------------------------------------------
-- api_key: API keys issued to tenants for authentication
-- ----------------------------------------------------------------------------
CREATE TABLE api_key (
    api_key_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key_hash        VARCHAR(255)  NOT NULL UNIQUE,
    key_prefix      VARCHAR(20)   NOT NULL,
    tenant_id       VARCHAR(255)  NOT NULL REFERENCES tenant(tenant_id),
    subject         VARCHAR(255)  NOT NULL,
    roles           VARCHAR(1000),
    status          VARCHAR(50)   NOT NULL DEFAULT 'active',
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(255)
);

CREATE INDEX idx_api_key_tenant ON api_key(tenant_id);
CREATE INDEX idx_api_key_status ON api_key(status);

-- ----------------------------------------------------------------------------
-- idp_config: per-tenant external identity provider configuration
-- ----------------------------------------------------------------------------
CREATE TABLE idp_config (
    idp_config_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       VARCHAR(255)  NOT NULL REFERENCES tenant(tenant_id),
    provider        VARCHAR(50)   NOT NULL,
    issuer_url      VARCHAR(1000),
    client_id       VARCHAR(255),
    jwks_uri        VARCHAR(1000),
    config_json     TEXT,
    status          VARCHAR(50)   NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(255),
    UNIQUE (tenant_id, provider)
);

-- ----------------------------------------------------------------------------
-- rate_limit_counter: per-tenant, per-month counters (source of truth for
-- monthly quotas; Redis sliding window handles burst/minute limits)
-- ----------------------------------------------------------------------------
CREATE TABLE rate_limit_counter (
    tenant_id       VARCHAR(255)  NOT NULL REFERENCES tenant(tenant_id),
    metric          VARCHAR(50)   NOT NULL,
    period          VARCHAR(10)   NOT NULL,
    counter         BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, metric, period)
);
