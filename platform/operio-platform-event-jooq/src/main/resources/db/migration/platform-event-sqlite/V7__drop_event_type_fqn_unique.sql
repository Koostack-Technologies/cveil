-- Multiple event types may share the same aggregate root class.
-- Recreate the table without the unique constraint on fqn.

CREATE TABLE event_type_new (
    event_type_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    fqn             TEXT NOT NULL
);

INSERT INTO event_type_new (name, display_name, fqn)
    SELECT name, display_name, fqn FROM event_type;

DROP TABLE event_type;

ALTER TABLE event_type_new RENAME TO event_type;
