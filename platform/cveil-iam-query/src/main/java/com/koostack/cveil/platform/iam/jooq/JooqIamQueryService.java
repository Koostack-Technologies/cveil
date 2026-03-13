package com.koostack.cveil.platform.iam.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.AuthResult;
import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.models.IdpUser;
import com.koostack.cveil.core.platform.iam.IamQueryService;
import com.koostack.cveil.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakConfig;
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
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-backed platform IAM query service.
 *
 * <p>Reads users, service accounts, and roles from Keycloak Admin API. Also provides authentication
 * (ROPC grant) and JWT verification (JWKS).
 */
public class JooqIamQueryService implements IamQueryService<JooqIamQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqIamQueryService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KeycloakConfig config;
    private Keycloak adminClient;
    private JwksTokenVerifier jwksVerifier;
    private HttpClient httpClient;
    private javax.sql.DataSource platformDataSource;
    private final ConcurrentHashMap<String, String> clientSecretCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "keycloak-iam-query";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqIamQueryConfig> getConfigurationType() {
        return JooqIamQueryConfig.class;
    }

    @Override
    public void configure(JooqIamQueryConfig cfg) {
        this.config = cfg.keycloakConfig();
        this.platformDataSource = cfg.platformDataSource();
        this.adminClient =
                KeycloakBuilder.builder()
                        .serverUrl(config.serverUrl())
                        .realm(config.adminRealm())
                        .clientId(config.adminClientId())
                        .username(config.adminUsername())
                        .password(config.adminPassword())
                        .build();
        this.jwksVerifier = new JwksTokenVerifier();
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        LOG.info("KeycloakIamQueryService configured");
    }

    @Override
    public boolean isReady() {
        return adminClient != null;
    }

    // -- User queries ---------------------------------------------------------

    @Override
    public List<IdpUser> listUsers(String realmId) {
        return adminClient.realm(realmId).users().list().stream().map(this::toIdpUser).toList();
    }

    @Override
    public IdpUser getUser(String realmId, String userId) {
        String kcUserId = resolveKcUserId(realmId, userId);
        if (kcUserId == null) return null;
        try {
            UserRepresentation user =
                    adminClient.realm(realmId).users().get(kcUserId).toRepresentation();
            return toIdpUser(user);
        } catch (jakarta.ws.rs.NotFoundException e) {
            return null;
        }
    }

    @Override
    public IdpUser findUserByEmail(String realmId, String email) {
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByEmail(email, true);
        return users.isEmpty() ? null : toIdpUser(users.get(0));
    }

    @Override
    public IdpUser findUserByUsername(String realmId, String username) {
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByUsername(username, true);
        return users.isEmpty() ? null : toIdpUser(users.get(0));
    }

    @Override
    public Set<String> getUserRoles(String realmId, String userId) {
        String kcUserId = resolveKcUserId(realmId, userId);
        if (kcUserId == null) return Set.of();
        return fetchRoles(realmId, kcUserId);
    }

    @Override
    public Set<String> getUserRolesByEmail(String realmId, String email) {
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByEmail(email, true);
        if (users.isEmpty()) return Set.of();
        return fetchRoles(realmId, users.get(0).getId());
    }

    @Override
    public Set<String> getUserRolesByUsername(String realmId, String username) {
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByUsername(username, true);
        if (users.isEmpty()) return Set.of();
        return fetchRoles(realmId, users.get(0).getId());
    }

    @Override
    public String resolveUserId(String realmId, String emailOrUsername) {
        // Try by email first
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByEmail(emailOrUsername, true);
        if (users.isEmpty()) {
            // Try by username
            users = adminClient.realm(realmId).users().searchByUsername(emailOrUsername, true);
        }
        if (users.isEmpty()) return null;
        return readCveilUserId(users.get(0));
    }

    private Set<String> fetchRoles(String realmId, String kcUserId) {
        try {
            return adminClient
                    .realm(realmId)
                    .users()
                    .get(kcUserId)
                    .roles()
                    .realmLevel()
                    .listEffective()
                    .stream()
                    .map(RoleRepresentation::getName)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Set.of();
        }
    }

    // -- Access token queries -------------------------------------------------

    @Override
    public List<com.koostack.cveil.core.platform.iam.AccessTokenInfo> listAccessTokens(
            String tenantId) {
        return List.of(); // Legacy service — use KeycloakIamQueryDispatcher
    }

    @Override
    public List<com.koostack.cveil.core.platform.iam.AccessTokenInfo> listAccessTokensByCreator(
            String tenantId, String createdBy) {
        return List.of(); // Legacy service — use KeycloakIamQueryDispatcher
    }

    @Override
    public com.koostack.cveil.core.platform.iam.AccessTokenInfo getAccessToken(
            String tenantId, String tokenPrefix) {
        return null; // Legacy service — use KeycloakIamQueryDispatcher
    }

    @Override
    public com.koostack.cveil.core.platform.iam.AccessTokenInfo findAccessTokenByHash(
            String tokenHash) {
        return null; // Legacy service — use KeycloakIamQueryDispatcher
    }

    // -- Authentication -------------------------------------------------------

    @Override
    public AuthResult authenticate(String realmId, String email, String password) {
        try {
            String clientSecret = resolveClientSecret(realmId);
            String tokenUrl =
                    config.serverUrl() + "/realms/" + realmId + "/protocol/openid-connect/token";

            String formBody =
                    "grant_type=password"
                            + "&client_id="
                            + urlEncode(config.cveilClientId())
                            + "&client_secret="
                            + urlEncode(clientSecret)
                            + "&username="
                            + urlEncode(email)
                            + "&password="
                            + urlEncode(password);

            LOG.info(
                    "ROPC authenticate: url={}, client_id={}, username={}",
                    tokenUrl,
                    config.cveilClientId(),
                    email);

            HttpResponse<String> response =
                    httpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(tokenUrl))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .timeout(HTTP_TIMEOUT)
                                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warn("ROPC failed: status={}, body={}", response.statusCode(), response.body());
                throw new CveilAuthException("Invalid email or password");
            }
            return parseTokenResponse(response.body(), realmId);
        } catch (CveilAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new CveilAuthException("Authentication failed: " + e.getMessage());
        }
    }

    @Override
    public AuthResult refreshToken(String realmId, String refreshToken) {
        try {
            String clientSecret = resolveClientSecret(realmId);
            String tokenUrl =
                    config.serverUrl() + "/realms/" + realmId + "/protocol/openid-connect/token";

            String formBody =
                    "grant_type=refresh_token"
                            + "&client_id="
                            + urlEncode(config.cveilClientId())
                            + "&client_secret="
                            + urlEncode(clientSecret)
                            + "&refresh_token="
                            + urlEncode(refreshToken);

            HttpResponse<String> response =
                    httpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(tokenUrl))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .timeout(HTTP_TIMEOUT)
                                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new CveilAuthException("Invalid or expired refresh token");
            }
            return parseTokenResponse(response.body(), realmId);
        } catch (CveilAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new CveilAuthException("Token refresh failed: " + e.getMessage());
        }
    }

    @Override
    public IdentityContext verifyToken(String realmId, String accessToken) {
        String jwksUrl =
                config.serverUrl() + "/realms/" + realmId + "/protocol/openid-connect/certs";
        return jwksVerifier.verify(jwksUrl, accessToken, realmId);
    }

    @Override
    public void logout(String realmId, String refreshToken) {
        try {
            String clientSecret = resolveClientSecret(realmId);
            String logoutUrl =
                    config.serverUrl() + "/realms/" + realmId + "/protocol/openid-connect/logout";

            String formBody =
                    "client_id="
                            + urlEncode(config.cveilClientId())
                            + "&client_secret="
                            + urlEncode(clientSecret)
                            + "&refresh_token="
                            + urlEncode(refreshToken);

            httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(logoutUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOG.warn("Logout failed for realm '{}': {}", realmId, e.getMessage());
        }
    }

    // (Legacy API key support removed — use FindAccessTokenByHashQueryActuator)

    // -- Internal helpers -----------------------------------------------------

    /** Resolves a command-side userId (cveil-user-id attribute) to the actual Keycloak user ID. */
    private String resolveKcUserId(String realmId, String userId) {
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByAttributes("cveil-user-id:" + userId);
        if (!users.isEmpty()) return users.get(0).getId();
        return null;
    }

    private IdpUser toIdpUser(UserRepresentation user) {
        String status =
                Boolean.TRUE.equals(user.isEnabled())
                        ? (Boolean.TRUE.equals(user.isEmailVerified()) ? "active" : "unverified")
                        : "suspended";
        String fullName =
                ((user.getFirstName() != null ? user.getFirstName() : "")
                                + " "
                                + (user.getLastName() != null ? user.getLastName() : ""))
                        .trim();
        Long created = user.getCreatedTimestamp();
        return new IdpUser(
                user.getUsername(),
                user.getEmail(),
                fullName.isEmpty() ? null : fullName,
                status,
                created != null ? Instant.ofEpochMilli(created) : Instant.now());
    }

    private String readCveilUserId(UserRepresentation user) {
        var attrs = user.getAttributes();
        if (attrs != null && attrs.containsKey("cveil-user-id")) {
            var values = attrs.get("cveil-user-id");
            if (values != null && !values.isEmpty()) {
                return values.get(0);
            }
        }
        return null;
    }

    private String resolveClientSecret(String realmId) {
        String cached = clientSecretCache.get(realmId);
        if (cached != null) return cached;

        if (config.cveilClientSecret() != null) {
            clientSecretCache.put(realmId, config.cveilClientSecret());
            return config.cveilClientSecret();
        }

        try {
            RealmResource realm = adminClient.realm(realmId);
            var clients = realm.clients().findByClientId(config.cveilClientId());
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
        throw new CveilAuthException("No client secret available for realm '" + realmId + "'");
    }

    private AuthResult parseTokenResponse(String json, String realmId) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String accessToken =
                    root.has("access_token") ? root.get("access_token").asText() : null;
            String refreshTokenVal =
                    root.has("refresh_token") ? root.get("refresh_token").asText() : null;
            long expiresIn = root.has("expires_in") ? root.get("expires_in").asLong() : 900;

            if (accessToken == null) {
                throw new CveilAuthException("Keycloak returned no access_token");
            }

            IdentityContext identity = verifyToken(realmId, accessToken);
            return AuthResult.bearer(accessToken, refreshTokenVal, expiresIn, identity);
        } catch (CveilAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new CveilAuthException("Failed to parse token response: " + e.getMessage());
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
