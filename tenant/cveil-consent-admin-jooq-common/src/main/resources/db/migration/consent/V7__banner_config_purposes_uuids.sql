-- V7: Add purposes_uuids column to banner_config for referential integrity.
-- The original 'purposes' column stores purpose names (subject to rename drift).
-- purposes_uuids stores purpose UUIDs, which are immutable.
-- Both columns are maintained; public API prefers UUIDs with name fallback.

ALTER TABLE banner_config ADD COLUMN IF NOT EXISTS purposes_uuids TEXT DEFAULT '[]';
