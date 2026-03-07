package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;

/**
 * Configuration for {@link KeycloakTenantIamQueryService}.
 *
 * @param keycloakConfig the Keycloak connection configuration
 */
public record KeycloakTenantIamQueryConfig(KeycloakConfig keycloakConfig) {
    public KeycloakTenantIamQueryConfig {
        if (keycloakConfig == null) {
            throw new IllegalArgumentException("keycloakConfig must not be null");
        }
    }
}
