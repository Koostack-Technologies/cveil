-- Rename payload column to changes (stores JSON Patch records)
ALTER TABLE event RENAME COLUMN payload TO changes;
ALTER INDEX idx_event_payload RENAME TO idx_event_changes;
