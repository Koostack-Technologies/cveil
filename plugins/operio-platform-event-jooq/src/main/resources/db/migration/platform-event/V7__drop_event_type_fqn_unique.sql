-- Multiple event types may share the same aggregate root class (e.g.
-- consent.policy.granted and consent.policy.revoked both use
-- ConsentPolicyAggregate). Recreate the table without the unique
-- constraint on fqn so that registerEventType inserts succeed for all
-- event types.
--
-- H2 does not support ALTER TABLE ... DROP CONSTRAINT on unique
-- constraints cleanly, so we recreate the table.

CREATE TABLE event_type_new (
    event_type_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    display_name    VARCHAR(255) NOT NULL,
    fqn             VARCHAR(512) NOT NULL
);

INSERT INTO event_type_new (name, display_name, fqn)
    SELECT name, display_name, fqn FROM event_type;

DROP TABLE event_type;

ALTER TABLE event_type_new RENAME TO event_type;
