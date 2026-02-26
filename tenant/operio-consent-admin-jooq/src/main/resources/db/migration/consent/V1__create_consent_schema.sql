-- ============================================================================
-- V1: Consent Administration Schema
-- ============================================================================
-- Recommended schema for the Operio Consent Admin plugin.
-- Uses standard SQL; tested against PostgreSQL and H2.
-- Users may bring their own schema as long as the expected structure is met.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- entity: optional logical grouping of fields (e.g. "Patient", "Order")
-- ----------------------------------------------------------------------------
CREATE TABLE entity (
    entity_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_name VARCHAR(255)  NOT NULL UNIQUE,
    description VARCHAR(1000),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(255),
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(255)
);

-- ----------------------------------------------------------------------------
-- field: every piece of data that can be governed by consent
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- category: a named bucket that fields can be assigned to so consent can be
--           granted or revoked at the bucket level rather than per-field
-- ----------------------------------------------------------------------------
CREATE TABLE category (
    category_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_name VARCHAR(255)  NOT NULL UNIQUE,
    description   VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(255)
);

-- ----------------------------------------------------------------------------
-- field_category: many-to-many assignment of fields into categories
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- consent_policy: a single consent decision linking a subject to either a
--                 specific field or a category.  Exactly one of field_id or
--                 category_id must be populated (never both, never neither).
-- ----------------------------------------------------------------------------
CREATE TABLE consent_policy (
    consent_policy_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id        VARCHAR(255) NOT NULL,
    field_id          BIGINT       REFERENCES field(field_id),
    category_id       BIGINT       REFERENCES category(category_id),
    granted           BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMP,
    granted_by        VARCHAR(255),
    granted_at        TIMESTAMP,
    revoked_by        VARCHAR(255),
    revoked_at        TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(255),
    UNIQUE (subject_id, field_id),
    UNIQUE (subject_id, category_id),
    CHECK (
        (field_id IS NOT NULL AND category_id IS NULL)
        OR (field_id IS NULL AND category_id IS NOT NULL)
    )
);

CREATE INDEX idx_consent_policy_subject ON consent_policy(subject_id);
