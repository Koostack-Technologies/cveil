package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.event.EventService;

/**
 * Configuration for {@link KeycloakTenantIamCommandService}.
 *
 * @param eventService the tenant event service for publishing domain events
 */
public record KeycloakTenantIamCommandConfig(EventService<?> eventService) {
    public KeycloakTenantIamCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }
}
