package com.koostack.operio.plugin.platform.keycloak;

/**
 * Configuration for the Keycloak IdP provider.
 *
 * @param serverUrl Keycloak base URL (e.g. {@code http://localhost:8180})
 * @param adminRealm admin realm name (typically {@code master})
 * @param adminClientId admin client ID for the admin REST API
 * @param adminUsername admin username
 * @param adminPassword admin password
 * @param operioClientId the client ID Operio uses within each tenant realm (e.g. {@code
 *     operio-backend})
 * @param operioClientSecret the client secret for the Operio client in each tenant realm
 *     (auto-generated during realm provisioning if null)
 */
public record KeycloakConfig(
        String serverUrl,
        String adminRealm,
        String adminClientId,
        String adminUsername,
        String adminPassword,
        String operioClientId,
        String operioClientSecret) {
    public KeycloakConfig {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("serverUrl must not be blank");
        }
    }

    public static KeycloakConfig of(String serverUrl, String adminUsername, String adminPassword) {
        return new KeycloakConfig(
                serverUrl,
                "master",
                "admin-cli",
                adminUsername,
                adminPassword,
                "operio-backend",
                null);
    }
}
