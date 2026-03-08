package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.event.EventService;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import javax.sql.DataSource;
import org.keycloak.admin.client.Keycloak;

/**
 * Configuration for {@link KeycloakIamDispatcher}.
 *
 * @param eventService the platform event service for publishing domain events
 * @param platformDataSource optional DataSource for legacy API key operations
 * @param keycloakAdmin the Keycloak admin client for IAM side-effects (nullable in test)
 * @param keycloakConfig the Keycloak configuration for OIDC auth operations (nullable in test)
 */
public record JooqIamCommandConfig(
        EventService<?> eventService, DataSource platformDataSource, Keycloak keycloakAdmin,
        KeycloakConfig keycloakConfig) {
    public JooqIamCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }

    /** Backward-compatible constructor — no KeycloakConfig. */
    public JooqIamCommandConfig(
            EventService<?> eventService, DataSource platformDataSource, Keycloak keycloakAdmin) {
        this(eventService, platformDataSource, keycloakAdmin, null);
    }

    public JooqIamCommandConfig(EventService<?> eventService) {
        this(eventService, null, null, null);
    }

    public JooqIamCommandConfig(EventService<?> eventService, DataSource platformDataSource) {
        this(eventService, platformDataSource, null, null);
    }
}
