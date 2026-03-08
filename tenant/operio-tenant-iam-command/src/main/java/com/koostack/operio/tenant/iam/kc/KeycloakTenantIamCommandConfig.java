package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.event.EventService;
import org.keycloak.admin.client.Keycloak;

/**
 * Configuration for {@link KeycloakTenantIamDispatcher}.
 *
 * @param eventService the tenant event service for publishing domain events
 * @param keycloakAdmin the Keycloak admin client for synchronous side-effects (nullable for tests)
 */
public record KeycloakTenantIamCommandConfig(EventService<?> eventService, Keycloak keycloakAdmin) {

    /** Backward-compatible constructor — no Keycloak client. */
    public KeycloakTenantIamCommandConfig(EventService<?> eventService) {
        this(eventService, null);
    }

    public KeycloakTenantIamCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }
}
