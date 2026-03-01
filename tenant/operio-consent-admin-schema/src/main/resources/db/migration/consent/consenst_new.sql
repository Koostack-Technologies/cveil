-- ----------------------------------------------------------------------------
-- 1. entity: Logical grouping of fields (e.g. "Patient", "Order")
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
-- 2. category: Groups fields for broad consent (e.g. "Contact Info")
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
-- 3. field: Specific data points governed by consent
-- ----------------------------------------------------------------------------
CREATE TABLE field (
                       field_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       field_name  VARCHAR(255)  NOT NULL,
                       description VARCHAR(1000),
                       entity_id   BIGINT        REFERENCES entity(entity_id),
                       category_id BIGINT        REFERENCES category(category_id),
                       created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       created_by  VARCHAR(255),
                       updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_by  VARCHAR(255),
                       UNIQUE (field_id, category_id)
);

-- ----------------------------------------------------------------------------
-- 4. purpose: Scopes for data usage (e.g. "marketing", "billing")
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
-- 5. regulation: Legal frameworks (e.g. "GDPR", "CCPA")
-- ----------------------------------------------------------------------------
CREATE TABLE regulation (
                            regulation_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            regulation_name VARCHAR(255)  NOT NULL UNIQUE,
                            description     VARCHAR(1000),
                            created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            created_by      VARCHAR(255)
);
-- ----------------------------------------------------------------------------
-- 6. policy: The current state of a subject's consent
-- ----------------------------------------------------------------------------
CREATE TABLE policy (
                                policy_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                purpose_id        BIGINT       NOT NULL REFERENCES purpose(purpose_id),
                                field_id          BIGINT       REFERENCES field(field_id),
                                category_id       BIGINT       REFERENCES category(category_id),
                                policy_type
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

    -- Constraints from V4: XOR for field/category and purpose-based uniqueness
                                CONSTRAINT uq_consent_field_purpose
                                    UNIQUE (subject_id, field_id, purpose_id),
                                CONSTRAINT uq_consent_category_purpose
                                    UNIQUE (subject_id, category_id, purpose_id),
                                CONSTRAINT ck_consent_policy_field_xor_category
                                    CHECK (
                                        (field_id IS NOT NULL AND category_id IS NULL)
                                            OR (field_id IS NULL AND category_id IS NOT NULL)
                                        )
);


-- ----------------------------------------------------------------------------
-- 6. consent_policy: The current state of a subject's consent
-- ----------------------------------------------------------------------------
CREATE TABLE consent_policy (
                                consent_policy_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                subject_id        VARCHAR(255) NOT NULL,
                                purpose_id        BIGINT       NOT NULL REFERENCES purpose(purpose_id),
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

    -- Constraints from V4: XOR for field/category and purpose-based uniqueness
                                CONSTRAINT uq_consent_field_purpose
                                    UNIQUE (subject_id, field_id, purpose_id),
                                CONSTRAINT uq_consent_category_purpose
                                    UNIQUE (subject_id, category_id, purpose_id),
                                CONSTRAINT ck_consent_policy_field_xor_category
                                    CHECK (
                                        (field_id IS NOT NULL AND category_id IS NULL)
                                            OR (field_id IS NULL AND category_id IS NOT NULL)
                                        )
);

-- ----------------------------------------------------------------------------
-- 7. consent_audit_log: Append-only history of all changes
-- ----------------------------------------------------------------------------
CREATE TABLE consent_audit_log (
                                   audit_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                   subject_id    VARCHAR(255)  NOT NULL,
                                   field_id      BIGINT        REFERENCES field(field_id),
                                   category_id   BIGINT        REFERENCES category(category_id),
                                   purpose_id    BIGINT        REFERENCES purpose(purpose_id),
                                   action        VARCHAR(20)   NOT NULL, -- 'GRANTED', 'REVOKED'
                                   granted       BOOLEAN       NOT NULL,
                                   expires_at    TIMESTAMP,
                                   actor         VARCHAR(255),
                                   ip_address    VARCHAR(45),
                                   user_agent    VARCHAR(1000),
                                   metadata      TEXT,
                                   created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- 8. dsar_audit_log: Record of data erasure requests
-- ----------------------------------------------------------------------------
CREATE TABLE dsar_audit_log (
                                dsar_id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                subject_id             VARCHAR(255)  NOT NULL,
                                deletion_request_id    VARCHAR(36)   NOT NULL,
                                policies_deleted       INT           NOT NULL DEFAULT 0,
                                cached_keys_deleted    INT           NOT NULL DEFAULT 0,
                                audit_entries_deleted  INT           NOT NULL DEFAULT 0,
                                status                 VARCHAR(20)   NOT NULL, -- 'SUCCESS', 'PARTIAL_FAILURE'
                                actor                  VARCHAR(255),
                                ip_address             VARCHAR(45),
                                user_agent             VARCHAR(1000),
                                metadata               TEXT,
                                created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- Core Indexes
-- ----------------------------------------------------------------------------
CREATE INDEX idx_consent_policy_subject ON consent_policy(subject_id);
CREATE INDEX idx_audit_subject_created ON consent_audit_log(subject_id, created_at DESC);
CREATE INDEX idx_dsar_subject_created ON dsar_audit_log(subject_id, created_at DESC);