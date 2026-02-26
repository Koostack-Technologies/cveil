-- Rename the JSON Patch 'changes' column to semantic 'payload'
ALTER TABLE event RENAME COLUMN changes TO payload;
