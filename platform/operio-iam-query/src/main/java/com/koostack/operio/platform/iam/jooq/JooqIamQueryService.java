package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.core.platform.iam.UserIdMapping;
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
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-backed platform IAM query service.
 *
 * <p>Reads users, service accounts, and roles from Keycloak Admin API. Also provides
 * authentication (ROPC grant) and JWT verification (JWKS).
 */
public class JooqIamQueryService implements IamQueryService<JooqIamQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqIamQueryService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

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
        this.adminClient = KeycloakBuilder.builder()
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
        return adminClient.realm(realmId).users().list().stream()
                .map(this::toIdpUser)
                .toList();
    }

    @Override
    public IdpUser getUser(String realmId, String userId) {
        String kcUserId = resolveKcUserId(realmId, userId);
        if (kcUserId == null) return null;
        try {
            UserRepresentation user = adminClient.realm(realmId).users().get(kcUserId)
                    .toRepresentation();
            return toIdpUser(user, userId);
        } catch (jakarta.ws.rs.NotFoundException e) {
            return null;
        }
    }

    @Override
    public IdpUser findUserByEmail(String realmId, String email) {
        List<UserRepresentation> users = adminClient.realm(realmId).users()
                .searchByEmail(email, true);
        return users.isEmpty() ? null : toIdpUser(users.get(0));
    }

    @Override
    public Set<String> getUserRoles(String realmId, String userId) {
        String kcUserId = resolveKcUserId(realmId, userId);
        if (kcUserId == null) return Set.of();
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
    public List<ServiceAccountInfo> listServiceAccounts(String realmId) {
        RealmResource realm = adminClient.realm(realmId);
        return realm.clients().findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.isServiceAccountsEnabled()))
                .filter(c -> !config.operioClientId().equals(c.getClientId()))
                .map(c -> toServiceAccountInfo(c, realmId))
                .toList();
    }

    @Override
    public ServiceAccountInfo getServiceAccount(String realmId, String clientId) {
        RealmResource realm = adminClient.realm(realmId);
        List<ClientRepresentation> clients = realm.clients().findByClientId(clientId);
        if (clients.isEmpty()) return null;
        return toServiceAccountInfo(clients.get(0), realmId);
    }

    // -- Authentication -------------------------------------------------------

    @Override
    public AuthResult authenticate(String realmId, String email, String password) {
        try {
            String clientSecret = resolveClientSecret(realmId);
            String tokenUrl = config.serverUrl() + "/realms/" + realmId
                    + "/protocol/openid-connect/token";

            String formBody = "grant_type=password"
                    + "&client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&username=" + urlEncode(email)
                    + "&password=" + urlEncode(password);

            LOG.info("ROPC authenticate: url={}, client_id={}, username={}", tokenUrl, config.operioClientId(), email);

            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(tokenUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warn("ROPC failed: status={}, body={}", response.statusCode(), response.body());
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
    public AuthResult refreshToken(String realmId, String refreshToken) {
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
    public IdentityContext verifyToken(String realmId, String accessToken) {
        String jwksUrl = config.serverUrl() + "/realms/" + realmId
                + "/protocol/openid-connect/certs";
        return jwksVerifier.verify(jwksUrl, accessToken, realmId);
    }

    @Override
    public void logout(String realmId, String refreshToken) {
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
            LOG.warn("Logout failed for realm '{}': {}", realmId, e.getMessage());
        }
    }

    // -- Legacy API key support -----------------------------------------------

    @SuppressWarnings("deprecation")
    @Override
    public ApiKeyInfo findByKeyHash(String keyHash) {
        if (platformDataSource == null) return null;
        String sql =
                "SELECT key_prefix, name, tenant_id, subject, roles, status, created_at, expires_at "
                        + "FROM platform_iam.api_key WHERE key_hash = ?";
        try (var conn = platformDataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setString(1, keyHash);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ApiKeyInfo(
                        rs.getString("key_prefix"),
                        rs.getString("key_prefix"),
                        rs.getString("name"),
                        rs.getString("tenant_id"),
                        null,
                        rs.getString("subject"),
                        rs.getString("roles"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at") != null
                                ? rs.getTimestamp("created_at").toInstant()
                                : null,
                        rs.getTimestamp("expires_at") != null
                                ? rs.getTimestamp("expires_at").toInstant()
                                : null);
            }
        } catch (Exception e) {
            LOG.warn("Failed to look up API key by hash: {}", e.getMessage());
            return null;
        }
    }

    // -- Internal helpers -----------------------------------------------------

    /**
     * Resolves a command-side userId to the actual Keycloak user ID.
     * Uses the in-memory mapping from IamProjectionHandler, with KC direct lookup as fallback.
     */
    private String resolveKcUserId(String realmId, String userId) {
        String cached = UserIdMapping.toKcId(userId);
        if (cached != null) return cached;
        try {
            adminClient.realm(realmId).users().get(userId).toRepresentation();
            return userId;
        } catch (jakarta.ws.rs.NotFoundException e) {
            return null;
        }
    }

    private IdpUser toIdpUser(UserRepresentation user) {
        return toIdpUser(user, null);
    }

    private IdpUser toIdpUser(UserRepresentation user, String commandUserId) {
        String status = Boolean.TRUE.equals(user.isEnabled())
                ? (Boolean.TRUE.equals(user.isEmailVerified()) ? "active" : "unverified")
                : "suspended";
        String fullName = ((user.getFirstName() != null ? user.getFirstName() : "")
                + " " + (user.getLastName() != null ? user.getLastName() : "")).trim();
        Long created = user.getCreatedTimestamp();
        String userId = commandUserId;
        if (userId == null) {
            userId = UserIdMapping.toCommandId(user.getId());
        }
        if (userId == null) {
            userId = user.getId();
        }
        return new IdpUser(
                userId,
                user.getEmail(),
                fullName.isEmpty() ? null : fullName,
                status,
                created != null ? Instant.ofEpochMilli(created) : Instant.now());
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

    private String resolveClientSecret(String realmId) {
        String cached = clientSecretCache.get(realmId);
        if (cached != null) return cached;

        if (config.operioClientSecret() != null) {
            clientSecretCache.put(realmId, config.operioClientSecret());
            return config.operioClientSecret();
        }

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
        String accessToken = extractJsonStringValue(json, "access_token");
        String refreshTokenVal = extractJsonStringValue(json, "refresh_token");
        String expiresInStr = extractJsonNumberValue(json, "expires_in");

        if (accessToken == null) {
            throw new OperioAuthException("Keycloak returned no access_token");
        }

        long expiresIn = expiresInStr != null ? Long.parseLong(expiresInStr) : 900;
        IdentityContext identity = verifyToken(realmId, accessToken);
        return AuthResult.bearer(accessToken, refreshTokenVal, expiresIn, identity);
    }

    private static String extractJsonStringValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;
        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return null;
        int quoteStart = json.indexOf('"', colonIndex + 1);
        if (quoteStart < 0) return null;
        int pos = quoteStart + 1;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '"' && json.charAt(pos - 1) != '\\') {
                return json.substring(quoteStart + 1, pos);
            }
            pos++;
        }
        return null;
    }

    private static String extractJsonNumberValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;
        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return null;
        int start = colonIndex + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
        return start < end ? json.substring(start, end) : null;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
