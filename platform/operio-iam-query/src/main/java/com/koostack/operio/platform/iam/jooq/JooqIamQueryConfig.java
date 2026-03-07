package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import javax.sql.DataSource;

/**
 * Configuration for {@link JooqIamQueryService}.
 *
 * @param keycloakConfig the Keycloak connection configuration
 * @param platformDataSource optional DataSource for legacy API key lookups
 */
public record JooqIamQueryConfig(KeycloakConfig keycloakConfig, DataSource platformDataSource) {
    public JooqIamQueryConfig {
        if (keycloakConfig == null) {
            throw new IllegalArgumentException("keycloakConfig must not be null");
        }
    }

    public JooqIamQueryConfig(KeycloakConfig keycloakConfig) {
        this(keycloakConfig, null);
    }
}
