package com.koostack.operio.tenant.iam.kc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService;
import com.koostack.operio.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-backed tenant IAM query service.
 *
 * <p>Reads users, service accounts, and roles from the tenant's Keycloak realm. Also provides
 * authentication (ROPC grant) and JWT verification (JWKS).
 *
 * <p>All operations translate the {@code tenantId} to a realm ID via the {@code t_{tenantId}}
 * convention.
 */
public class KeycloakTenantIamQueryService
        implements TenantIamQueryService<KeycloakTenantIamQueryConfig> {

    private static final Logger LOG =
            LoggerFactory.getLogger(KeycloakTenantIamQueryService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KeycloakConfig config;
    private Keycloak adminClient;
    private JwksTokenVerifier jwksVerifier;
    private HttpClient httpClient;
    private final ConcurrentHashMap<String, String> clientSecretCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "keycloak-tenant-iam-query";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<KeycloakTenantIamQueryConfig> getConfigurationType() {
        return KeycloakTenantIamQueryConfig.class;
    }

    @Override
    public void configure(KeycloakTenantIamQueryConfig cfg) {
        this.config = cfg.keycloakConfig();
        this.adminClient = KeycloakBuilder.builder()
                .serverUrl(config.serverUrl())
                .realm(config.adminRealm())
                .clientId(config.adminClientId())
                .username(config.adminUsername())
                .password(config.adminPassword())
                .build();
        this.jwksVerifier = new JwksTokenVerifier();
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        LOG.info("KeycloakTenantIamQueryService configured");
    }

    @Override
    public boolean isReady() {
        return adminClient != null;
    }

    // -- User queries ---------------------------------------------------------

    @Override
    public List<IdpUser> listUsers(String tenantId) {
        String realmId = toRealmId(tenantId);
        return adminClient.realm(realmId).users().list().stream()
                .map(this::toIdpUser)
                .toList();
    }

    @Override
    public IdpUser getUser(String tenantId, String userId) {
        String realmId = toRealmId(tenantId);
        String kcUserId = resolveKcUserId(realmId, userId);
        if (kcUserId == null) return null;
        try {
            UserRepresentation user = adminClient.realm(realmId).users().get(kcUserId)
                    .toRepresentation();
            return toIdpUser(user);
        } catch (jakarta.ws.rs.NotFoundException e) {
            return null;
        }
    }

    @Override
    public IdpUser findUserByEmail(String tenantId, String email) {
        String realmId = toRealmId(tenantId);
        List<UserRepresentation> users = adminClient.realm(realmId).users()
                .searchByEmail(email, true);
        return users.isEmpty() ? null : toIdpUser(users.get(0));
    }

    @Override
    public IdpUser findUserByUsername(String tenantId, String username) {
        String realmId = toRealmId(tenantId);
        List<UserRepresentation> users = adminClient.realm(realmId).users()
                .searchByUsername(username, true);
        return users.isEmpty() ? null : toIdpUser(users.get(0));
    }

    @Override
    public Set<String> getUserRoles(String tenantId, String userId) {
        String realmId = toRealmId(tenantId);
        String kcUserId = resolveKcUserId(realmId, userId);
        if (kcUserId == null) return Set.of();
        return fetchRoles(realmId, kcUserId);
    }

    @Override
    public Set<String> getUserRolesByEmail(String tenantId, String email) {
        String realmId = toRealmId(tenantId);
        List<UserRepresentation> users = adminClient.realm(realmId).users()
                .searchByEmail(email, true);
        if (users.isEmpty()) return Set.of();
        return fetchRoles(realmId, users.get(0).getId());
    }

    @Override
    public Set<String> getUserRolesByUsername(String tenantId, String username) {
        String realmId = toRealmId(tenantId);
        List<UserRepresentation> users = adminClient.realm(realmId).users()
                .searchByUsername(username, true);
        if (users.isEmpty()) return Set.of();
        return fetchRoles(realmId, users.get(0).getId());
    }

    @Override
    public String resolveUserId(String tenantId, String emailOrUsername) {
        String realmId = toRealmId(tenantId);
        // Try by email first
        List<UserRepresentation> users = adminClient.realm(realmId).users()
                .searchByEmail(emailOrUsername, true);
        if (users.isEmpty()) {
            // Try by username
            users = adminClient.realm(realmId).users()
                    .searchByUsername(emailOrUsername, true);
        }
        if (users.isEmpty()) return null;
        return readOperioUserId(users.get(0));
    }

    private Set<String> fetchRoles(String realmId, String kcUserId) {
        try {
            return adminClient.realm(realmId).users().get(kcUserId)
                    .roles().realmLevel().listEffective().stream()
                    .map(RoleRepresentation::getName)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Set.of();
        }
    }

    // -- Service account queries ----------------------------------------------

    @Override
    public List<ServiceAccountInfo> listServiceAccounts(String tenantId) {
        String realmId = toRealmId(tenantId);
        RealmResource realm = adminClient.realm(realmId);
        return realm.clients().findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.isServiceAccountsEnabled()))
                .filter(c -> !config.operioClientId().equals(c.getClientId()))
                .map(c -> toServiceAccountInfo(c, realmId))
                .toList();
    }

    @Override
    public ServiceAccountInfo getServiceAccount(String tenantId, String clientId) {
        String realmId = toRealmId(tenantId);
        RealmResource realm = adminClient.realm(realmId);
        List<ClientRepresentation> clients = realm.clients().findByClientId(clientId);
        if (clients.isEmpty()) return null;
        return toServiceAccountInfo(clients.get(0), realmId);
    }

    // -- Authentication -------------------------------------------------------

    @Override
    public AuthResult authenticate(String tenantId, String email, String password) {
        String realmId = toRealmId(tenantId);
        try {
            String clientSecret = resolveClientSecret(realmId);
            String tokenUrl = config.serverUrl() + "/realms/" + realmId
                    + "/protocol/openid-connect/token";

            String formBody = "grant_type=password"
                    + "&client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&username=" + urlEncode(email)
                    + "&password=" + urlEncode(password);

            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(tokenUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new OperioAuthException("Invalid email or password");
            }
            return parseTokenResponse(response.body(), realmId);
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioAuthException("Authentication failed: " + e.getMessage());
        }
    }

    @Override
    public AuthResult refreshToken(String tenantId, String refreshToken) {
        String realmId = toRealmId(tenantId);
        try {
            String clientSecret = resolveClientSecret(realmId);
            String tokenUrl = config.serverUrl() + "/realms/" + realmId
                    + "/protocol/openid-connect/token";

            String formBody = "grant_type=refresh_token"
                    + "&client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&refresh_token=" + urlEncode(refreshToken);

            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(tokenUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new OperioAuthException("Invalid or expired refresh token");
            }
            return parseTokenResponse(response.body(), realmId);
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioAuthException("Token refresh failed: " + e.getMessage());
        }
    }

    @Override
    public IdentityContext verifyToken(String tenantId, String accessToken) {
        String realmId = toRealmId(tenantId);
        String jwksUrl = config.serverUrl() + "/realms/" + realmId
                + "/protocol/openid-connect/certs";
        return jwksVerifier.verify(jwksUrl, accessToken, realmId);
    }

    @Override
    public void logout(String tenantId, String refreshToken) {
        String realmId = toRealmId(tenantId);
        try {
            String clientSecret = resolveClientSecret(realmId);
            String logoutUrl = config.serverUrl() + "/realms/" + realmId
                    + "/protocol/openid-connect/logout";

            String formBody = "client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&refresh_token=" + urlEncode(refreshToken);

            httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(logoutUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOG.warn("Logout failed for tenant '{}': {}", tenantId, e.getMessage());
        }
    }

    // -- Internal helpers -----------------------------------------------------

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }

    private IdpUser toIdpUser(UserRepresentation user) {
        String status = Boolean.TRUE.equals(user.isEnabled())
                ? (Boolean.TRUE.equals(user.isEmailVerified()) ? "active" : "unverified")
                : "suspended";
        String fullName = ((user.getFirstName() != null ? user.getFirstName() : "")
                + " " + (user.getLastName() != null ? user.getLastName() : "")).trim();
        Long created = user.getCreatedTimestamp();
        return new IdpUser(
                user.getUsername(),
                user.getEmail(),
                fullName.isEmpty() ? null : fullName,
                status,
                created != null ? Instant.ofEpochMilli(created) : Instant.now());
    }

    private String readOperioUserId(UserRepresentation user) {
        var attrs = user.getAttributes();
        if (attrs != null && attrs.containsKey("operio-user-id")) {
            var values = attrs.get("operio-user-id");
            if (values != null && !values.isEmpty()) {
                return values.get(0);
            }
        }
        return null;
    }

    private ServiceAccountInfo toServiceAccountInfo(
            ClientRepresentation client, String realmId) {
        return new ServiceAccountInfo(
                client.getClientId(),
                client.getName(),
                realmId,
                Set.of(),
                Boolean.TRUE.equals(client.isEnabled()) ? "active" : "disabled",
                null);
    }

    private String resolveKcUserId(String realmId, String commandUserId) {
        List<UserRepresentation> users = adminClient.realm(realmId).users()
                .searchByAttributes("operio-user-id:" + commandUserId);
        if (!users.isEmpty()) return users.get(0).getId();
        return null;
    }

    private String resolveClientSecret(String realmId) {
        String cached = clientSecretCache.get(realmId);
        if (cached != null) return cached;

        // Each tenant realm has its own client secret — always resolve per-realm
        // (don't use config.operioClientSecret() which is the platform realm secret)
        try {
            RealmResource realm = adminClient.realm(realmId);
            var clients = realm.clients().findByClientId(config.operioClientId());
            if (!clients.isEmpty()) {
                String internalId = clients.get(0).getId();
                String secret = realm.clients().get(internalId).getSecret().getValue();
                if (secret != null && !secret.isBlank()) {
                    clientSecretCache.put(realmId, secret);
                    return secret;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve client secret for realm '{}': {}", realmId, e.getMessage());
        }
        throw new OperioAuthException("No client secret available for realm '" + realmId + "'");
    }

    private AuthResult parseTokenResponse(String json, String realmId) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String accessToken = root.has("access_token") ? root.get("access_token").asText() : null;
            String refreshTokenVal = root.has("refresh_token") ? root.get("refresh_token").asText() : null;
            long expiresIn = root.has("expires_in") ? root.get("expires_in").asLong() : 900;

            if (accessToken == null) {
                throw new OperioAuthException("Keycloak returned no access_token");
            }

            IdentityContext identity = verifyToken(realmIdToTenantId(realmId), accessToken);
            return AuthResult.bearer(accessToken, refreshTokenVal, expiresIn, identity);
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioAuthException("Failed to parse token response: " + e.getMessage());
        }
    }

    private static String realmIdToTenantId(String realmId) {
        return realmId.startsWith("t_") ? realmId.substring(2) : realmId;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
