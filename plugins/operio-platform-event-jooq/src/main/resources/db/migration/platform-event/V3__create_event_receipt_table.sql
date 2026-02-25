-- Append-only receipt log: consumers register that they received an event.

CREATE TABLE event_receipt (
    receipt_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        BIGINT       NOT NULL,
    consumer_name   VARCHAR(255) NOT NULL,
    received_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (event_id, consumer_name)
);

CREATE INDEX idx_event_receipt_event    ON event_receipt (event_id);
CREATE INDEX idx_event_receipt_consumer ON event_receipt (consumer_name);
