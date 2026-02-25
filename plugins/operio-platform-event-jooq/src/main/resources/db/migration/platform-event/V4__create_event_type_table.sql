-- Event type lookup table (pre-seeded reference data).

CREATE TABLE event_type (
    event_type_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    display_name    VARCHAR(255) NOT NULL,
    fqn             VARCHAR(512) NOT NULL UNIQUE
);

INSERT INTO event_type (name, display_name, fqn) VALUES
    ('TENANT_ONBOARDING_STARTED',  'Tenant Onboarding Started',  'com.koostack.operio.core.event.models.TenantOnboardingStarted'),
    ('TENANT_ONBOARDING_FINISHED', 'Tenant Onboarding Finished', 'com.koostack.operio.core.event.models.TenantOnboardingFinished'),
    ('CONSENT_REVOKED',            'Consent Revoked',            'com.koostack.operio.core.event.models.ConsentRevoked'),
    ('CONSENT_GRANTED',            'Consent Granted',            'com.koostack.operio.core.event.models.ConsentGranted');
