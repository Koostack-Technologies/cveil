-- Rename changes column back to payload (stores semantic domain event JSON)
ALTER TABLE event RENAME COLUMN changes TO payload;
