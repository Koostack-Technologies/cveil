package com.koostack.operio.tenant.authz.keycloak;

/**
 * Configuration for the Keycloak-backed {@link KeycloakAuthzProvider}.
 *
 * @param serverUrl the Keycloak server base URL (e.g. {@code http://localhost:8080})
 * @param adminRealm the admin realm (typically {@code "master"})
 * @param adminClientId the admin client ID (typically {@code "admin-cli"})
 * @param adminUsername the admin username
 * @param adminPassword the admin password
 */
public record KeycloakAuthzConfig(
        String serverUrl,
        String adminRealm,
        String adminClientId,
        String adminUsername,
        String adminPassword) {
    public KeycloakAuthzConfig {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("serverUrl must not be blank");
        }
        if (adminRealm == null || adminRealm.isBlank()) {
            throw new IllegalArgumentException("adminRealm must not be blank");
        }
        if (adminClientId == null || adminClientId.isBlank()) {
            throw new IllegalArgumentException("adminClientId must not be blank");
        }
        if (adminUsername == null || adminUsername.isBlank()) {
            throw new IllegalArgumentException("adminUsername must not be blank");
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalArgumentException("adminPassword must not be blank");
        }
    }
}
