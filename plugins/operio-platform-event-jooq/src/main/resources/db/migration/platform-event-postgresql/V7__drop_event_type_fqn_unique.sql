-- Multiple event types may share the same aggregate root class (e.g.
-- consent.policy.granted and consent.policy.revoked both use
-- ConsentPolicyAggregate). Drop the unique constraint on fqn so that
-- registerEventType inserts succeed for all event types.

ALTER TABLE event_type DROP CONSTRAINT event_type_fqn_key;
