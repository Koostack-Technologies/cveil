-- Add published flag for outbox pattern

ALTER TABLE event ADD COLUMN published BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_event_unpublished ON event (published) WHERE published = FALSE;
