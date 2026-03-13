package com.koostack.cveil.plugin.platform.keycloak;

/**
 * Configuration for the Keycloak IdP provider.
 *
 * @param serverUrl Keycloak base URL (e.g. {@code http://localhost:8180})
 * @param adminRealm admin realm name (typically {@code master})
 * @param adminClientId admin client ID for the admin REST API
 * @param adminUsername admin username
 * @param adminPassword admin password
 * @param cveilClientId the client ID Cveil uses within the cveil realm (e.g. {@code
 *     cveil-backend})
 * @param cveilClientSecret the client secret for the Cveil client (auto-resolved if null)
 * @param cveilRealmName the single Keycloak realm name (default: {@code cveil})
 */
public record KeycloakConfig(
        String serverUrl,
        String adminRealm,
        String adminClientId,
        String adminUsername,
        String adminPassword,
        String cveilClientId,
        String cveilClientSecret,
        String cveilRealmName) {
    public KeycloakConfig {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("serverUrl must not be blank");
        }
        if (cveilRealmName == null || cveilRealmName.isBlank()) {
            cveilRealmName = "cveil";
        }
    }

    /** Backward-compatible 7-arg constructor — defaults cveilRealmName to "cveil". */
    public KeycloakConfig(
            String serverUrl,
            String adminRealm,
            String adminClientId,
            String adminUsername,
            String adminPassword,
            String cveilClientId,
            String cveilClientSecret) {
        this(
                serverUrl,
                adminRealm,
                adminClientId,
                adminUsername,
                adminPassword,
                cveilClientId,
                cveilClientSecret,
                "cveil");
    }

    public static KeycloakConfig of(String serverUrl, String adminUsername, String adminPassword) {
        return new KeycloakConfig(
                serverUrl,
                "master",
                "admin-cli",
                adminUsername,
                adminPassword,
                "cveil-backend",
                null,
                "cveil");
    }
}
