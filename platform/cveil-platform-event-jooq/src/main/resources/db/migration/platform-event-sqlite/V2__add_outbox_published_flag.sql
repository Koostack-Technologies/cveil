-- Add published flag for outbox pattern
-- SQLite does not support partial indexes, so a standard index is used.

ALTER TABLE event ADD COLUMN published INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_event_unpublished ON event (published);
