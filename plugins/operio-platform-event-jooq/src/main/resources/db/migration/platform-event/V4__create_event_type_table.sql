-- Event type lookup table (pre-seeded reference data).

CREATE TABLE event_type (
    event_type_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    display_name    VARCHAR(255) NOT NULL
);

INSERT INTO event_type (name, display_name) VALUES
    ('TENANT_ONBOARDING_STARTED',  'Tenant Onboarding Started'),
    ('TENANT_ONBOARDING_FINISHED', 'Tenant Onboarding Finished'),
    ('CONSENT_REVOKED',            'Consent Revoked'),
    ('CONSENT_GRANTED',            'Consent Granted');
