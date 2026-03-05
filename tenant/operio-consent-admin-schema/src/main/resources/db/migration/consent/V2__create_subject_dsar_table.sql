-- ============================================================================
-- V2: Create subject_dsar table & remove dsar_key from subject
-- ============================================================================
-- Moves the per-subject DSAR encryption key into a dedicated table keyed
-- by a deterministic UUID derived from the subject name.  This supports the
-- "digital shredder" pattern: deleting a row from subject_dsar renders all
-- encrypted event payloads for that subject permanently unreadable (GDPR
-- Article 17 — right to be forgotten).
-- ============================================================================

-- 1. New table — keyed by dsar_id (UUID derived in application code via
--    UUID.nameUUIDFromBytes("dsar:" + subjectName))
CREATE TABLE subject_dsar (
    dsar_id     UUID          NOT NULL PRIMARY KEY,
    dsar_key    VARCHAR(512)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Drop the dsar_key column from subject (now lives in subject_dsar)
ALTER TABLE subject DROP COLUMN dsar_key;
