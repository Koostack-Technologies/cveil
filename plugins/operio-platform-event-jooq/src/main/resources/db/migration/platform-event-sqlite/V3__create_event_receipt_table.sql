-- Append-only receipt log: consumers register that they received an event.

CREATE TABLE event_receipt (
    receipt_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id        INTEGER NOT NULL,
    consumer_name   TEXT    NOT NULL,
    received_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE (event_id, consumer_name)
);

CREATE INDEX idx_event_receipt_event    ON event_receipt (event_id);
CREATE INDEX idx_event_receipt_consumer ON event_receipt (consumer_name);
