package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.plugin.platform.keycloak.KeycloakConfig;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakOrganizationService;
import javax.sql.DataSource;
import org.jooq.DSLContext;

/**
 * Configuration for {@link KeycloakIamQueryDispatcher}.
 *
 * @param keycloakConfig the Keycloak connection configuration
 * @param platformDataSource optional DataSource for access token DB queries
 * @param orgService the Keycloak Organizations service (nullable — only needed for identity
 *     queries)
 * @param platformDsl jOOQ context for local IAM mirror tables
 */
public record JooqIamQueryConfig(
        KeycloakConfig keycloakConfig,
        DataSource platformDataSource,
        KeycloakOrganizationService orgService,
        DSLContext platformDsl) {
    public JooqIamQueryConfig {
        if (keycloakConfig == null) {
            throw new IllegalArgumentException("keycloakConfig must not be null");
        }
        if (platformDsl == null) {
            throw new IllegalArgumentException("platformDsl must not be null");
        }
    }
}
