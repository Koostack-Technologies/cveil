-- ============================================================================
-- V2: Add purpose_name to consent_policy
-- ============================================================================
-- Adds a NOT NULL purpose_name column so that consent decisions are scoped to
-- a specific purpose (e.g. "marketing", "analytics", "operations").
--
-- The unique constraints are widened from:
--     (subject_id, field_id)          → (subject_id, field_id, purpose_name)
--     (subject_id, category_id)       → (subject_id, category_id, purpose_name)
--
-- Because the V1 constraints are unnamed (auto-generated names differ between
-- H2 and Postgres), we recreate the table to cleanly replace them.
--
-- Existing rows receive the purpose_name 'default'.
-- ============================================================================

-- 1. Create the new table with purpose_name and named constraints -----------
CREATE TABLE consent_policy_v2 (
    consent_policy_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id        VARCHAR(255) NOT NULL,
    purpose_name      VARCHAR(255) NOT NULL,
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
    CONSTRAINT uq_consent_policy_subject_field_purpose
        UNIQUE (subject_id, field_id, purpose_name),
    CONSTRAINT uq_consent_policy_subject_category_purpose
        UNIQUE (subject_id, category_id, purpose_name),
    CONSTRAINT ck_consent_policy_field_xor_category
        CHECK (
            (field_id IS NOT NULL AND category_id IS NULL)
            OR (field_id IS NULL AND category_id IS NOT NULL)
        )
);

-- 2. Migrate existing rows with purpose_name = 'default' --------------------
INSERT INTO consent_policy_v2 (
    subject_id, purpose_name, field_id, category_id, granted, expires_at,
    granted_by, granted_at, revoked_by, revoked_at,
    created_at, created_by, updated_at, updated_by
)
SELECT
    subject_id, 'default', field_id, category_id, granted, expires_at,
    granted_by, granted_at, revoked_by, revoked_at,
    created_at, created_by, updated_at, updated_by
FROM consent_policy;

-- 3. Replace the old table --------------------------------------------------
DROP TABLE consent_policy;
ALTER TABLE consent_policy_v2 RENAME TO consent_policy;

-- 4. Recreate the subject index ---------------------------------------------
CREATE INDEX idx_consent_policy_subject ON consent_policy(subject_id);
