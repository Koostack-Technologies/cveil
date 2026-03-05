-- ============================================================================
-- V3: Add digital shredder columns to tenant event store (SQLite)
-- ============================================================================
-- Supports per-subject payload encryption (digital shredder pattern).
--   • shreddable  — 1 when the payload is encrypted with a subject DSAR key
--   • dsar_id     — TEXT UUID linking to subject_dsar for key lookup
-- ============================================================================

ALTER TABLE event ADD COLUMN shreddable INTEGER NOT NULL DEFAULT 0;
ALTER TABLE event ADD COLUMN dsar_id    TEXT;

CREATE INDEX idx_event_shreddable ON event (shreddable);
CREATE INDEX idx_event_dsar_id    ON event (dsar_id);
