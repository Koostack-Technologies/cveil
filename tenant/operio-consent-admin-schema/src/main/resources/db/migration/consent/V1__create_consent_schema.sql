-- ============================================================================
-- V1: Consent Administration Schema (Clean Reset)
-- ============================================================================
-- Operio Consent Admin schema with aggregate-boundary-aligned tables.
-- Aggregates: Field, Purpose, Regulation, Subject, SubjectConsent, DSAR.
-- Uses standard SQL; tested against PostgreSQL and H2.
-- ============================================================================

-- ============================================================================
-- 0. Types & Enums
-- ============================================================================
CREATE TYPE masking_type AS ENUM ('PLAINTEXT', 'REDACT_ALL', 'REDACT_UP_TO_LAST_4', 'HASH256', 'REDACT_LOCAL');
CREATE TYPE policy_type AS ENUM ('FIELD', 'CATEGORY');
CREATE TYPE consent_action AS ENUM ('GRANT', 'REVOKE', 'EXPIRE');

-- ============================================================================
-- 1. Field Aggregate (entity, field, category, field_category)
-- ============================================================================
CREATE TABLE entity (
    entity_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_name VARCHAR(255)  NOT NULL UNIQUE,
    description VARCHAR(1000),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(255),
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(255)
);

CREATE TABLE category (
    category_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_name VARCHAR(255)  NOT NULL UNIQUE,
    description   VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(255)
);

CREATE TABLE field (
    field_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    field_name  VARCHAR(255)  NOT NULL,
    description VARCHAR(1000),
    entity_id   BIGINT        REFERENCES entity(entity_id),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(255),
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(255),
    UNIQUE (field_name, entity_id)
);

CREATE INDEX idx_field_entity ON field(entity_id);

CREATE TABLE field_category (
    field_category_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    field_id          BIGINT    NOT NULL REFERENCES field(field_id),
    category_id       BIGINT    NOT NULL REFERENCES category(category_id),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(255),
    UNIQUE (field_id, category_id)
);

-- ============================================================================
-- 2. Regulation Aggregate (regulation, jurisdiction, regulation_jurisdiction)
-- ============================================================================
CREATE TABLE regulation (
    regulation_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    regulation_name VARCHAR(255)  NOT NULL UNIQUE,
    description     VARCHAR(1000),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(255)
);

CREATE TABLE jurisdiction (
    jurisdiction_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    jurisdiction_name VARCHAR(255)  NOT NULL UNIQUE,
    description       VARCHAR(1000),
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(255)
);

CREATE TABLE regulation_jurisdiction (
    regulation_jurisdiction_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    regulation_id             BIGINT NOT NULL REFERENCES regulation(regulation_id),
    jurisdiction_id           BIGINT NOT NULL REFERENCES jurisdiction(jurisdiction_id),
    created_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                VARCHAR(255),
    updated_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                VARCHAR(255),
    UNIQUE (regulation_id, jurisdiction_id)
);

-- ============================================================================
-- 3. Purpose Aggregate (purpose, purpose_regulation, policy)
-- ============================================================================
CREATE TABLE purpose (
    purpose_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purpose_name  VARCHAR(255)  NOT NULL UNIQUE,
    description   VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(255)
);

CREATE TABLE purpose_regulation (
    purpose_regulation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purpose_id            BIGINT NOT NULL REFERENCES purpose(purpose_id),
    regulation_id         BIGINT NOT NULL REFERENCES regulation(regulation_id),
    on_granted            masking_type NOT NULL DEFAULT 'PLAINTEXT',
    on_denied             masking_type NOT NULL DEFAULT 'REDACT_ALL',
    lawful_basis          VARCHAR(255),
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(255),
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by            VARCHAR(255),
    UNIQUE (purpose_id, regulation_id)
);

CREATE TABLE policy (
    policy_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
    UNIQUE (purpose_id, field_id),
    UNIQUE (purpose_id, category_id)
);

-- ============================================================================
-- 4. Subject Aggregate (subject)
-- ============================================================================
CREATE TABLE subject (
    subject_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_name  VARCHAR(255)  NOT NULL UNIQUE,
    dsar_key      VARCHAR(512)  NOT NULL,
    description   VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(255)
);

-- ============================================================================
-- 5. SubjectConsent Aggregate (consent_ledger — append-only historical record)
-- ============================================================================
CREATE TABLE consent_ledger (
    consent_ledger_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_id         BIGINT NOT NULL REFERENCES policy(policy_id),
    subject_id        VARCHAR(255) NOT NULL REFERENCES subject(subject_name),
    action            consent_action NOT NULL,
    actor             VARCHAR(255),
    ip_address        VARCHAR(45),
    user_agent        VARCHAR(1000),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP
);

CREATE INDEX idx_ledger_subject ON consent_ledger(subject_id);
CREATE INDEX idx_ledger_subject_policy_ts ON consent_ledger(subject_id, policy_id, created_at DESC);

-- ============================================================================
-- 6. DSAR Aggregate (dsar_audit_log)
-- ============================================================================
CREATE TABLE dsar_audit_log (
    dsar_id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dsar_subject ON dsar_audit_log(subject_id);
CREATE INDEX idx_dsar_created ON dsar_audit_log(created_at DESC);
CREATE INDEX idx_dsar_subject_created ON dsar_audit_log(subject_id, created_at DESC);
CREATE UNIQUE INDEX idx_dsar_deletion_request_id ON dsar_audit_log(deletion_request_id);
