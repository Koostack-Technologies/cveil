package com.koostack.operio.plugin.platform.keycloak;

/**
 * Configuration for the Keycloak IdP provider.
 *
 * @param serverUrl Keycloak base URL (e.g. {@code http://localhost:8180})
 * @param adminRealm admin realm name (typically {@code master})
 * @param adminClientId admin client ID for the admin REST API
 * @param adminUsername admin username
 * @param adminPassword admin password
 * @param operioClientId the client ID Operio uses within the operio realm (e.g. {@code
 *     operio-backend})
 * @param operioClientSecret the client secret for the Operio client (auto-resolved if null)
 * @param operioRealmName the single Keycloak realm name (default: {@code operio})
 */
public record KeycloakConfig(
        String serverUrl,
        String adminRealm,
        String adminClientId,
        String adminUsername,
        String adminPassword,
        String operioClientId,
        String operioClientSecret,
        String operioRealmName) {
    public KeycloakConfig {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("serverUrl must not be blank");
        }
        if (operioRealmName == null || operioRealmName.isBlank()) {
            operioRealmName = "operio";
        }
    }

    /** Backward-compatible 7-arg constructor — defaults operioRealmName to "operio". */
    public KeycloakConfig(
            String serverUrl,
            String adminRealm,
            String adminClientId,
            String adminUsername,
            String adminPassword,
            String operioClientId,
            String operioClientSecret) {
        this(
                serverUrl,
                adminRealm,
                adminClientId,
                adminUsername,
                adminPassword,
                operioClientId,
                operioClientSecret,
                "operio");
    }

    public static KeycloakConfig of(String serverUrl, String adminUsername, String adminPassword) {
        return new KeycloakConfig(
                serverUrl,
                "master",
                "admin-cli",
                adminUsername,
                adminPassword,
                "operio-backend",
                null,
                "operio");
    }
}
