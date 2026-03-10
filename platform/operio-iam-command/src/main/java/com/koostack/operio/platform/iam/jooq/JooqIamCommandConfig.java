package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.event.EventService;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import javax.sql.DataSource;
import org.keycloak.admin.client.Keycloak;
import redis.clients.jedis.JedisPooled;

/**
 * Configuration for {@link KeycloakIamDispatcher}.
 *
 * @param eventService the platform event service for publishing domain events
 * @param platformDataSource optional DataSource for legacy API key operations
 * @param keycloakAdmin the Keycloak admin client for IAM side-effects (nullable in test)
 * @param keycloakConfig the Keycloak configuration for OIDC auth operations (nullable in test)
 * @param orgService the Keycloak Organizations service for tenant management (nullable in test)
 * @param jedis the Redis connection for tenant Redis provisioning (nullable if Redis disabled)
 */
public record JooqIamCommandConfig(
        EventService<?> eventService,
        DataSource platformDataSource,
        Keycloak keycloakAdmin,
        KeycloakConfig keycloakConfig,
        KeycloakOrganizationService orgService,
        JedisPooled jedis) {
    public JooqIamCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }

    /** Full constructor without org service and Redis (backward compatible). */
    public JooqIamCommandConfig(
            EventService<?> eventService,
            DataSource platformDataSource,
            Keycloak keycloakAdmin,
            KeycloakConfig keycloakConfig) {
        this(eventService, platformDataSource, keycloakAdmin, keycloakConfig, null, null);
    }

    /** Backward-compatible constructor — no KeycloakConfig. */
    public JooqIamCommandConfig(
            EventService<?> eventService, DataSource platformDataSource, Keycloak keycloakAdmin) {
        this(eventService, platformDataSource, keycloakAdmin, null, null, null);
    }

    public JooqIamCommandConfig(EventService<?> eventService) {
        this(eventService, null, null, null, null, null);
    }

    public JooqIamCommandConfig(EventService<?> eventService, DataSource platformDataSource) {
        this(eventService, platformDataSource, null, null, null, null);
    }
}
