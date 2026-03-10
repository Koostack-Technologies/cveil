-- ============================================================================
-- V1: Unified Consent Administration Schema (multi-tenant)
-- ============================================================================
-- All consent tables include tenant_id for Postgres RLS isolation.
-- At runtime tables live in the 'operio_consent' schema.
-- At jOOQ-codegen time tables live in H2's PUBLIC schema.
-- Aggregates: Field, Purpose, Regulation, Subject, SubjectConsent, DSAR.
-- ============================================================================

-- ============================================================================
-- 0. Types & Enums
-- ============================================================================
CREATE TYPE masking_type   AS ENUM ('PLAINTEXT', 'REDACT_ALL', 'REDACT_UP_TO_LAST_4', 'HASH256', 'REDACT_LOCAL');
CREATE TYPE policy_type    AS ENUM ('FIELD', 'CATEGORY');
CREATE TYPE consent_action AS ENUM ('GRANT', 'REVOKE', 'EXPIRE');

-- ============================================================================
-- 1. Field Aggregate (entity, field, category, field_category)
-- ============================================================================
CREATE TABLE entity (
    entity_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   VARCHAR(255)  NOT NULL,
    entity_name VARCHAR(255)  NOT NULL,
    description VARCHAR(1000),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(255),
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(255),
    UNIQUE (tenant_id, entity_name)
);
CREATE INDEX idx_entity_tenant ON entity (tenant_id);

CREATE TABLE category (
    category_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     VARCHAR(255)  NOT NULL,
    category_name VARCHAR(255)  NOT NULL,
    description   VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(255),
    UNIQUE (tenant_id, category_name)
);
CREATE INDEX idx_category_tenant ON category (tenant_id);

CREATE TABLE field (
    field_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   VARCHAR(255)  NOT NULL,
    field_name  VARCHAR(255)  NOT NULL,
    description VARCHAR(1000),
    entity_id   BIGINT        REFERENCES entity(entity_id),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(255),
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(255),
    UNIQUE (tenant_id, field_name, entity_id)
);
CREATE INDEX idx_field_tenant ON field (tenant_id);
CREATE INDEX idx_field_entity ON field (entity_id);

CREATE TABLE field_category (
    field_category_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         VARCHAR(255) NOT NULL,
    field_id          BIGINT    NOT NULL REFERENCES field(field_id),
    category_id       BIGINT    NOT NULL REFERENCES category(category_id),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(255),
    UNIQUE (tenant_id, field_id, category_id)
);
CREATE INDEX idx_field_category_tenant ON field_category (tenant_id);

-- ============================================================================
-- 2. Regulation Aggregate (regulation, jurisdiction, regulation_jurisdiction)
-- ============================================================================
CREATE TABLE regulation (
    regulation_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       VARCHAR(255)  NOT NULL,
    regulation_name VARCHAR(255)  NOT NULL,
    description     VARCHAR(1000),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(255),
    UNIQUE (tenant_id, regulation_name)
);
CREATE INDEX idx_regulation_tenant ON regulation (tenant_id);

CREATE TABLE jurisdiction (
    jurisdiction_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         VARCHAR(255)  NOT NULL,
    jurisdiction_name VARCHAR(255)  NOT NULL,
    description       VARCHAR(1000),
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(255),
    UNIQUE (tenant_id, jurisdiction_name)
);
CREATE INDEX idx_jurisdiction_tenant ON jurisdiction (tenant_id);

CREATE TABLE regulation_jurisdiction (
    regulation_jurisdiction_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                 VARCHAR(255) NOT NULL,
    regulation_id             BIGINT NOT NULL REFERENCES regulation(regulation_id),
    jurisdiction_id           BIGINT NOT NULL REFERENCES jurisdiction(jurisdiction_id),
    created_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                VARCHAR(255),
    updated_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                VARCHAR(255),
    UNIQUE (tenant_id, regulation_id, jurisdiction_id)
);
CREATE INDEX idx_reg_juris_tenant ON regulation_jurisdiction (tenant_id);

-- ============================================================================
-- 3. Purpose Aggregate (purpose, purpose_regulation, policy)
-- ============================================================================
CREATE TABLE purpose (
    purpose_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     VARCHAR(255)  NOT NULL,
    purpose_name  VARCHAR(255)  NOT NULL,
    description   VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(255),
    UNIQUE (tenant_id, purpose_name)
);
CREATE INDEX idx_purpose_tenant ON purpose (tenant_id);

CREATE TABLE purpose_regulation (
    purpose_regulation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             VARCHAR(255) NOT NULL,
    purpose_id            BIGINT NOT NULL REFERENCES purpose(purpose_id),
    regulation_id         BIGINT NOT NULL REFERENCES regulation(regulation_id),
    on_granted            masking_type NOT NULL DEFAULT 'PLAINTEXT',
    on_denied             masking_type NOT NULL DEFAULT 'REDACT_ALL',
    lawful_basis          VARCHAR(255),
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(255),
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by            VARCHAR(255),
    UNIQUE (tenant_id, purpose_id, regulation_id)
);
CREATE INDEX idx_purpose_reg_tenant ON purpose_regulation (tenant_id);

CREATE TABLE policy (
    policy_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         VARCHAR(255) NOT NULL,
    purpose_id        BIGINT NOT NULL REFERENCES purpose(purpose_id),
    field_id          BIGINT REFERENCES field(field_id),
    category_id       BIGINT REFERENCES category(category_id),
    policy_type       policy_type NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(255),

    CONSTRAINT ck_policy_target_xor CHECK (
        (field_id IS NOT NULL AND category_id IS NULL) OR
        (field_id IS NULL AND category_id IS NOT NULL)
    ),
    UNIQUE (tenant_id, purpose_id, field_id),
    UNIQUE (tenant_id, purpose_id, category_id)
);
CREATE INDEX idx_policy_tenant ON policy (tenant_id);

-- ============================================================================
-- 4. Subject Aggregate (subject)
-- ============================================================================
CREATE TABLE subject (
    subject_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     VARCHAR(255)  NOT NULL,
    subject_name  VARCHAR(255)  NOT NULL,
    description   VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(255),
    UNIQUE (tenant_id, subject_name)
);
CREATE INDEX idx_subject_tenant ON subject (tenant_id);

-- ============================================================================
-- 5. SubjectConsent Aggregate (consent_ledger — append-only historical record)
-- ============================================================================
CREATE TABLE consent_ledger (
    consent_ledger_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         VARCHAR(255) NOT NULL,
    policy_id         BIGINT NOT NULL REFERENCES policy(policy_id),
    subject_id        VARCHAR(255) NOT NULL,
    action            consent_action NOT NULL,
    actor             VARCHAR(255),
    ip_address        VARCHAR(45),
    user_agent        VARCHAR(1000),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP,
    FOREIGN KEY (tenant_id, subject_id) REFERENCES subject(tenant_id, subject_name)
);
CREATE INDEX idx_ledger_tenant  ON consent_ledger (tenant_id);
CREATE INDEX idx_ledger_subject ON consent_ledger (subject_id);
CREATE INDEX idx_ledger_subject_policy_ts ON consent_ledger (subject_id, policy_id, created_at DESC);

-- ============================================================================
-- 6. DSAR Aggregate (dsar_audit_log)
-- ============================================================================
CREATE TABLE dsar_audit_log (
    dsar_id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              VARCHAR(255)  NOT NULL,
    subject_id             VARCHAR(255)  NOT NULL,
    deletion_request_id    VARCHAR(36)   NOT NULL,
    policies_deleted       INT           NOT NULL DEFAULT 0,
    cached_keys_deleted    INT           NOT NULL DEFAULT 0,
    audit_entries_deleted  INT           NOT NULL DEFAULT 0,
    status                 VARCHAR(20)   NOT NULL,
    actor                  VARCHAR(255),
    ip_address             VARCHAR(45),
    user_agent             VARCHAR(1000),
    metadata               TEXT,
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, deletion_request_id)
);
CREATE INDEX idx_dsar_tenant          ON dsar_audit_log (tenant_id);
CREATE INDEX idx_dsar_subject         ON dsar_audit_log (subject_id);
CREATE INDEX idx_dsar_created         ON dsar_audit_log (created_at DESC);
CREATE INDEX idx_dsar_subject_created ON dsar_audit_log (subject_id, created_at DESC);

-- ============================================================================
-- 7. Subject DSAR key (digital shredder — per-subject encryption key)
-- ============================================================================
CREATE TABLE subject_dsar (
    dsar_id    UUID          NOT NULL PRIMARY KEY,
    tenant_id  VARCHAR(255)  NOT NULL,
    dsar_key   VARCHAR(512)  NOT NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_subject_dsar_tenant ON subject_dsar (tenant_id);
