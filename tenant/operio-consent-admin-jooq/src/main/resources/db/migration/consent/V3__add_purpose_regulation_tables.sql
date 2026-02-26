-- ============================================================================
-- V3: Purpose, Regulation, Purpose-Regulation tables + begin purpose_id backfill
-- ============================================================================
-- Additive migration only — no columns are dropped, no constraints are removed.
-- Both purpose_name and purpose_id coexist on consent_policy after this
-- migration. V4 completes the cutover by dropping purpose_name.
--
-- Compatible with H2 (build-time jOOQ codegen) and PostgreSQL (production).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- purpose: first-class consent purpose (e.g. "marketing", "analytics",
--          "treatment", "operations")
-- ----------------------------------------------------------------------------
CREATE TABLE purpose (
    purpose_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purpose_name  VARCHAR(255)  NOT NULL UNIQUE,
    description   VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(255)
);

-- ----------------------------------------------------------------------------
-- regulation: legal frameworks (GDPR, CCPA, HIPAA, etc.)
-- ----------------------------------------------------------------------------
CREATE TABLE regulation (
    regulation_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    regulation_name VARCHAR(255)  NOT NULL UNIQUE,
    full_name       VARCHAR(1000),
    jurisdiction    VARCHAR(255),
    description     VARCHAR(2000),
    url             VARCHAR(1000),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(255)
);

-- ----------------------------------------------------------------------------
-- purpose_regulation: which regulations govern which purposes
-- ----------------------------------------------------------------------------
CREATE TABLE purpose_regulation (
    purpose_regulation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purpose_id            BIGINT        NOT NULL REFERENCES purpose(purpose_id),
    regulation_id         BIGINT        NOT NULL REFERENCES regulation(regulation_id),
    legal_basis           VARCHAR(500),
    description           VARCHAR(1000),
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(255),
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by            VARCHAR(255),
    UNIQUE (purpose_id, regulation_id)
);

-- ----------------------------------------------------------------------------
-- Backfill: insert distinct purpose_name values into purpose table
-- ----------------------------------------------------------------------------
INSERT INTO purpose (purpose_name, created_by)
    SELECT DISTINCT purpose_name, 'migration-v3'
    FROM consent_policy
    WHERE purpose_name IS NOT NULL;

-- ----------------------------------------------------------------------------
-- Add nullable purpose_id column (does NOT drop purpose_name yet)
-- ----------------------------------------------------------------------------
ALTER TABLE consent_policy ADD COLUMN purpose_id BIGINT;

-- ----------------------------------------------------------------------------
-- Backfill purpose_id from purpose_name using a correlated subquery.
-- Uses standard SQL syntax compatible with both H2 and PostgreSQL.
-- ----------------------------------------------------------------------------
UPDATE consent_policy
    SET purpose_id = (
        SELECT p.purpose_id
        FROM purpose p
        WHERE p.purpose_name = consent_policy.purpose_name
    )
    WHERE purpose_name IS NOT NULL;

-- ----------------------------------------------------------------------------
-- Add FK constraint (nullable for now — old code may still write
-- purpose_name only during the transition window)
-- ----------------------------------------------------------------------------
ALTER TABLE consent_policy
    ADD CONSTRAINT fk_consent_policy_purpose
    FOREIGN KEY (purpose_id) REFERENCES purpose(purpose_id);
