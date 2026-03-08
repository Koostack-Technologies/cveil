package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.event.EventService;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import org.keycloak.admin.client.Keycloak;

/**
 * Configuration for {@link KeycloakTenantIamDispatcher}.
 *
 * @param eventService the tenant event service for publishing domain events
 * @param keycloakAdmin the Keycloak admin client for synchronous side-effects (nullable for tests)
 * @param keycloakConfig the Keycloak configuration for OIDC auth operations (nullable for tests)
 */
public record KeycloakTenantIamCommandConfig(
        EventService<?> eventService, Keycloak keycloakAdmin, KeycloakConfig keycloakConfig) {

    /** Backward-compatible constructor — no Keycloak client or config. */
    public KeycloakTenantIamCommandConfig(EventService<?> eventService) {
        this(eventService, null, null);
    }

    /** Backward-compatible constructor — no KeycloakConfig. */
    public KeycloakTenantIamCommandConfig(EventService<?> eventService, Keycloak keycloakAdmin) {
        this(eventService, keycloakAdmin, null);
    }

    public KeycloakTenantIamCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }
}
