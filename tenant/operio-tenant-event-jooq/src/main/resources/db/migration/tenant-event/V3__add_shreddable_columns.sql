-- ============================================================================
-- V3: Add digital shredder columns to tenant event store
-- ============================================================================
-- Supports per-subject payload encryption (digital shredder pattern).
--   • shreddable  — TRUE when the payload is encrypted with a subject DSAR key
--   • dsar_id     — UUID linking to subject_dsar for key lookup / decryption
-- ============================================================================

ALTER TABLE event ADD COLUMN shreddable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE event ADD COLUMN dsar_id    UUID;

CREATE INDEX idx_event_shreddable ON event(shreddable);
CREATE INDEX idx_event_dsar_id    ON event(dsar_id);
