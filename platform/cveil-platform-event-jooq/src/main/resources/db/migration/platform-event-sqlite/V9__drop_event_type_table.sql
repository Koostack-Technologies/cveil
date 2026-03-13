-- Drop the event_type catalog table. Event types are stored as VARCHAR
-- directly in the event table and no longer require a separate catalog.
DROP TABLE IF EXISTS event_type;
