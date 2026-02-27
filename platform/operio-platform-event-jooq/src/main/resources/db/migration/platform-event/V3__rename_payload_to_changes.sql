-- Rename payload column to changes (stores JSON Patch records)
ALTER TABLE event RENAME COLUMN payload TO changes;
