package com.koostack.operio.tenant.iam.kc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared OIDC helper for tenant IAM auth actuators.
 *
 * <p>Encapsulates client-secret resolution, ROPC token exchange, refresh-token grant,
 * token revocation (logout), and token-response parsing — the logic previously
 * embedded in {@code KeycloakTenantIamQueryService}.
 *
 * <p>All operations translate {@code tenantId} to the Keycloak realm via {@code t_{tenantId}}.
 */
public final class TenantKeycloakAuthSupport {

    private static final Logger LOG = LoggerFactory.getLogger(TenantKeycloakAuthSupport.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KeycloakConfig config;
    private final Keycloak adminClient;
    private final JwksTokenVerifier jwksVerifier;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, String> clientSecretCache = new ConcurrentHashMap<>();

    public TenantKeycloakAuthSupport(KeycloakConfig config, Keycloak adminClient) {
        this.config = config;
        this.adminClient = adminClient;
        this.jwksVerifier = new JwksTokenVerifier();
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    // -- Public operations ----------------------------------------------------

    /**
     * ROPC (Resource Owner Password Credentials) grant for a tenant user.
     */
    public AuthResult authenticate(String tenantId, String email, String password) {
        String realmId = toRealmId(tenantId);
        try {
            String clientSecret = resolveClientSecret(realmId);
            String tokenUrl = tokenUrl(realmId);

            String formBody = "grant_type=password"
                    + "&client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&username=" + urlEncode(email)
                    + "&password=" + urlEncode(password);

            HttpResponse<String> response = post(tokenUrl, formBody);

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

    /**
     * Refresh-token grant for a tenant user.
     */
    public AuthResult refreshToken(String tenantId, String refreshToken) {
        String realmId = toRealmId(tenantId);
        try {
            String clientSecret = resolveClientSecret(realmId);
            String tokenUrl = tokenUrl(realmId);

            String formBody = "grant_type=refresh_token"
                    + "&client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&refresh_token=" + urlEncode(refreshToken);

            HttpResponse<String> response = post(tokenUrl, formBody);

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

    /**
     * Revoke a refresh token (OIDC logout) for a tenant user.
     */
    public void logout(String tenantId, String refreshToken) {
        String realmId = toRealmId(tenantId);
        try {
            String clientSecret = resolveClientSecret(realmId);
            String logoutUrl = config.serverUrl() + "/realms/" + realmId
                    + "/protocol/openid-connect/logout";

            String formBody = "client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&refresh_token=" + urlEncode(refreshToken);

            post(logoutUrl, formBody);
        } catch (Exception e) {
            LOG.warn("Logout failed for tenant '{}': {}", tenantId, e.getMessage());
        }
    }

    // -- Internal helpers -----------------------------------------------------

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }

    private static String realmIdToTenantId(String realmId) {
        return realmId.startsWith("t_") ? realmId.substring(2) : realmId;
    }

    private String tokenUrl(String realmId) {
        return config.serverUrl() + "/realms/" + realmId + "/protocol/openid-connect/token";
    }

    private HttpResponse<String> post(String url, String formBody) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(HTTP_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(formBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Each tenant realm has its own client secret — always resolved per-realm via the Admin API.
     */
    private String resolveClientSecret(String realmId) {
        String cached = clientSecretCache.get(realmId);
        if (cached != null) return cached;

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

            String jwksUrl = config.serverUrl() + "/realms/" + realmId
                    + "/protocol/openid-connect/certs";
            IdentityContext identity = jwksVerifier.verify(jwksUrl, accessToken, realmId);
            return AuthResult.bearer(accessToken, refreshTokenVal, expiresIn, identity);
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioAuthException("Failed to parse token response: " + e.getMessage());
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

