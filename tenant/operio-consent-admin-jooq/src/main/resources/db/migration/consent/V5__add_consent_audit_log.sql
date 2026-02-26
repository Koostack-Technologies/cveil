-- ============================================================================
-- V5: Add consent_audit_log — append-only audit trail for consent events
-- ============================================================================
-- This table lives per-tenant (in t_{tenantId} schemas alongside
-- consent_policy) and is append-only — rows are never updated or deleted
-- except by DSAR erasure (future). This is what regulators audit.
--
-- Compatible with H2 (build-time jOOQ codegen) and PostgreSQL (production).
-- ============================================================================

CREATE TABLE consent_audit_log (
    audit_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id    VARCHAR(255)  NOT NULL,
    field_id      BIGINT        REFERENCES field(field_id),
    category_id   BIGINT        REFERENCES category(category_id),
    purpose_id    BIGINT        REFERENCES purpose(purpose_id),
    action        VARCHAR(20)   NOT NULL,  -- 'GRANTED', 'REVOKED'
    granted       BOOLEAN       NOT NULL,
    expires_at    TIMESTAMP,
    actor         VARCHAR(255),
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(1000),
    metadata      TEXT,                     -- JSON for extensibility
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- Indexes for common query patterns
-- ---------------------------------------------------------------------------

-- Full history for a subject (DSAR export, admin dashboard)
CREATE INDEX idx_audit_subject ON consent_audit_log(subject_id);

-- Time-range scans (compliance reports)
CREATE INDEX idx_audit_created ON consent_audit_log(created_at);

-- Subject history ordered by recency (the most common query path)
CREATE INDEX idx_audit_subject_created ON consent_audit_log(subject_id, created_at DESC);
