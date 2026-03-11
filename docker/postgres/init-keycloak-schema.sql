-- Create the "keycloak" schema so Keycloak can store its tables separately
-- from the Operio application schemas in the same database.
CREATE SCHEMA IF NOT EXISTS keycloak;
