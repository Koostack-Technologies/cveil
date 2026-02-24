-- ============================================================================
-- V6: Add dsar_audit_log — append-only audit trail for DSAR (Data Subject Access Request) erasure operations
-- ============================================================================
-- This table lives per-tenant (in t_{tenantId} schemas alongside consent_policy
-- and consent_audit_log) and is append-only — rows are never updated or deleted.
-- It records deletion requests for forensic compliance auditing and regulatory
-- traceability (GDPR Article 17, CCPA §1798.105).
--
-- Unlike consent_audit_log (which gets deleted during DSAR erasure), this table
-- is preserved to prove that a deletion was requested and completed.
--
-- Compatible with H2 (build-time jOOQ codegen) and PostgreSQL (production).
-- ============================================================================

CREATE TABLE dsar_audit_log (
    dsar_id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id             VARCHAR(255)  NOT NULL,
    deletion_request_id    VARCHAR(36)   NOT NULL,  -- UUID as string for easier querying
    policies_deleted       INT           NOT NULL DEFAULT 0,
    cached_keys_deleted    INT           NOT NULL DEFAULT 0,
    audit_entries_deleted  INT           NOT NULL DEFAULT 0,
    status                 VARCHAR(20)   NOT NULL,  -- 'SUCCESS', 'PARTIAL_FAILURE'
    actor                  VARCHAR(255),             -- who initiated the deletion (e.g., system, user ID)
    ip_address             VARCHAR(45),              -- IPv4 or IPv6
    user_agent             VARCHAR(1000),            -- originating User-Agent header
    metadata               TEXT,                     -- JSON for extensibility (error details, etc.)
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- Indexes for common query patterns
-- ---------------------------------------------------------------------------

-- Full history of deletions for a subject (forensic audit)
CREATE INDEX idx_dsar_subject ON dsar_audit_log(subject_id);

-- Time-range scans (compliance reports, monthly reviews)
CREATE INDEX idx_dsar_created ON dsar_audit_log(created_at DESC);

-- Subject history ordered by recency (the most common forensic query)
CREATE INDEX idx_dsar_subject_created ON dsar_audit_log(subject_id, created_at DESC);

-- Lookup a specific deletion request by ID (for receipt verification)
CREATE UNIQUE INDEX idx_dsar_deletion_request_id ON dsar_audit_log(deletion_request_id);
