package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.event.EventService;
import javax.sql.DataSource;
import org.keycloak.admin.client.Keycloak;

/**
 * Configuration for {@link KeycloakIamDispatcher}.
 *
 * @param eventService the platform event service for publishing domain events
 * @param platformDataSource optional DataSource for legacy API key operations
 * @param keycloakAdmin the Keycloak admin client for IAM side-effects (nullable in test)
 */
public record JooqIamCommandConfig(
        EventService<?> eventService, DataSource platformDataSource, Keycloak keycloakAdmin) {
    public JooqIamCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }

    public JooqIamCommandConfig(EventService<?> eventService) {
        this(eventService, null, null);
    }

    public JooqIamCommandConfig(EventService<?> eventService, DataSource platformDataSource) {
        this(eventService, platformDataSource, null);
    }
}
