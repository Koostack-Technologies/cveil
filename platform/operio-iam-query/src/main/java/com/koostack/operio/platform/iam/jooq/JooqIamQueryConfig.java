package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import javax.sql.DataSource;

/**
 * Configuration for {@link KeycloakIamQueryDispatcher}.
 *
 * @param keycloakConfig the Keycloak connection configuration
 * @param platformDataSource optional DataSource for legacy API key lookups
 * @param orgService the Keycloak Organizations service for org queries (nullable in test)
 */
public record JooqIamQueryConfig(
        KeycloakConfig keycloakConfig,
        DataSource platformDataSource,
        KeycloakOrganizationService orgService) {
    public JooqIamQueryConfig {
        if (keycloakConfig == null) {
            throw new IllegalArgumentException("keycloakConfig must not be null");
        }
    }

    public JooqIamQueryConfig(KeycloakConfig keycloakConfig) {
        this(keycloakConfig, null, null);
    }

    public JooqIamQueryConfig(KeycloakConfig keycloakConfig, DataSource platformDataSource) {
        this(keycloakConfig, platformDataSource, null);
    }
}
