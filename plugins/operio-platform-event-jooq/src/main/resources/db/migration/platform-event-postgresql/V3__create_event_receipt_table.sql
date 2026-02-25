-- Append-only receipt log: consumers register that they received an event.
-- Partitioned by HASH on consumer_name for workload isolation.

CREATE TABLE event_receipt (
    receipt_id      BIGINT       GENERATED ALWAYS AS IDENTITY,
    event_id        BIGINT       NOT NULL,
    consumer_name   VARCHAR(255) NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (receipt_id, consumer_name),
    UNIQUE (event_id, consumer_name)
) PARTITION BY HASH (consumer_name);

CREATE TABLE event_receipt_p0 PARTITION OF event_receipt FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE event_receipt_p1 PARTITION OF event_receipt FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE event_receipt_p2 PARTITION OF event_receipt FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE event_receipt_p3 PARTITION OF event_receipt FOR VALUES WITH (MODULUS 4, REMAINDER 3);

CREATE INDEX idx_event_receipt_event    ON event_receipt (event_id);
CREATE INDEX idx_event_receipt_consumer ON event_receipt (consumer_name);
