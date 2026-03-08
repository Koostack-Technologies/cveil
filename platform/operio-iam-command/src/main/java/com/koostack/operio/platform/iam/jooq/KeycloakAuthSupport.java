package com.koostack.operio.platform.iam.jooq;

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
 * Shared OIDC helper for IAM auth actuators.
 *
 * <p>Encapsulates client-secret resolution, ROPC token exchange, refresh-token grant,
 * token revocation (logout), and token-response parsing — the logic previously
 * embedded in {@code JooqIamQueryService}.
 */
public final class KeycloakAuthSupport {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAuthSupport.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KeycloakConfig config;
    private final Keycloak adminClient;
    private final JwksTokenVerifier jwksVerifier;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, String> clientSecretCache = new ConcurrentHashMap<>();

    public KeycloakAuthSupport(KeycloakConfig config, Keycloak adminClient) {
        this.config = config;
        this.adminClient = adminClient;
        this.jwksVerifier = new JwksTokenVerifier();
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    // -- Public operations ----------------------------------------------------

    /**
     * ROPC (Resource Owner Password Credentials) grant.
     */
    public AuthResult authenticate(String realmId, String email, String password) {
        try {
            String clientSecret = resolveClientSecret(realmId);
            String tokenUrl = tokenUrl(realmId);

            String formBody = "grant_type=password"
                    + "&client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&username=" + urlEncode(email)
                    + "&password=" + urlEncode(password);

            LOG.info("ROPC authenticate: url={}, client_id={}, username={}",
                    tokenUrl, config.operioClientId(), email);

            HttpResponse<String> response = post(tokenUrl, formBody);

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

    /**
     * Refresh-token grant.
     */
    public AuthResult refreshToken(String realmId, String refreshToken) {
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
     * Revoke a refresh token (OIDC logout).
     */
    public void logout(String realmId, String refreshToken) {
        try {
            String clientSecret = resolveClientSecret(realmId);
            String logoutUrl = config.serverUrl() + "/realms/" + realmId
                    + "/protocol/openid-connect/logout";

            String formBody = "client_id=" + urlEncode(config.operioClientId())
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&refresh_token=" + urlEncode(refreshToken);

            post(logoutUrl, formBody);
        } catch (Exception e) {
            LOG.warn("Logout failed for realm '{}': {}", realmId, e.getMessage());
        }
    }

    // -- Internal helpers -----------------------------------------------------

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

