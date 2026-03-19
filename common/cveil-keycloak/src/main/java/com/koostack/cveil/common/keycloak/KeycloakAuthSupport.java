package com.koostack.cveil.common.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.AuthResult;
import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared OIDC helper for Keycloak-backed IAM auth operations against a single realm.
 *
 * <p>Encapsulates client-secret resolution, ROPC token exchange, refresh-token grant, token
 * revocation (logout), and token-response parsing.
 *
 * <p>All operations target the single {@code cveil} realm (configured via {@link
 * KeycloakConfig#cveilRealmName()}).
 */
public final class KeycloakAuthSupport {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAuthSupport.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KeycloakConfig config;
    private final Keycloak adminClient;
    private final JwksTokenVerifier jwksVerifier;
    private final HttpClient httpClient;
    private volatile String cachedClientSecret;

    public KeycloakAuthSupport(KeycloakConfig config, Keycloak adminClient) {
        this(config, adminClient, new JwksTokenVerifier());
    }

    /**
     * Constructor that accepts a shared {@link JwksTokenVerifier} so the JWKS cache is shared with
     * other components (e.g. the {@code ApiKeyAuthFilter} verification path).
     */
    public KeycloakAuthSupport(
            KeycloakConfig config, Keycloak adminClient, JwksTokenVerifier jwksVerifier) {
        this.config = config;
        this.adminClient = adminClient;
        this.jwksVerifier = jwksVerifier;
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
     * @deprecated Use the two-arg constructor. RealmResolver is no longer needed.
     */
    @Deprecated(forRemoval = true)
    public KeycloakAuthSupport(
            KeycloakConfig config, Keycloak adminClient, RealmResolver realmResolver) {
        this(config, adminClient);
    }

    // -- Public operations ----------------------------------------------------

    /**
     * ROPC (Resource Owner Password Credentials) grant.
     *
     * @param id ignored — all auth targets the single realm
     * @param email the user's email
     * @param password the user's password
     */
    public AuthResult authenticate(String id, String email, String password) {
        return authenticate(id, email, password, null);
    }

    /**
     * ROPC grant with optional DPoP proof.
     *
     * @param id ignored — all auth targets the single realm
     * @param email the user's email
     * @param password the user's password
     * @param dpopProof optional DPoP proof JWT to forward to the Keycloak token endpoint
     */
    public AuthResult authenticate(String id, String email, String password, String dpopProof) {
        String realmId = config.cveilRealmName();
        try {
            String clientSecret = resolveClientSecret();
            String tokenUrl = tokenUrl(realmId);

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
                    "ROPC authenticate: url={}, client_id={}, username={}, dpop={}",
                    tokenUrl,
                    config.cveilClientId(),
                    email,
                    dpopProof != null);

            HttpResponse<String> response = post(tokenUrl, formBody, dpopProof);

            if (response.statusCode() != 200) {
                LOG.warn("ROPC failed: status={}, body={}", response.statusCode(), response.body());
                throw new CveilAuthException("Invalid email or password");
            }
            return parseTokenResponse(response.body(), id);
        } catch (CveilAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new CveilAuthException("Authentication failed: " + e.getMessage());
        }
    }

    /**
     * Refresh-token grant.
     *
     * @param id tenant context identifier (passed through to IdentityContext)
     * @param refreshToken the refresh token
     */
    public AuthResult refreshToken(String id, String refreshToken) {
        return refreshToken(id, refreshToken, null);
    }

    /**
     * Refresh-token grant with optional DPoP proof.
     *
     * @param id tenant context identifier (passed through to IdentityContext)
     * @param refreshToken the refresh token
     * @param dpopProof optional DPoP proof JWT to forward to the Keycloak token endpoint
     */
    public AuthResult refreshToken(String id, String refreshToken, String dpopProof) {
        String realmId = config.cveilRealmName();
        try {
            String clientSecret = resolveClientSecret();
            String tokenUrl = tokenUrl(realmId);

            String formBody =
                    "grant_type=refresh_token"
                            + "&client_id="
                            + urlEncode(config.cveilClientId())
                            + "&client_secret="
                            + urlEncode(clientSecret)
                            + "&refresh_token="
                            + urlEncode(refreshToken);

            HttpResponse<String> response = post(tokenUrl, formBody, dpopProof);

            if (response.statusCode() != 200) {
                throw new CveilAuthException("Invalid or expired refresh token");
            }
            return parseTokenResponse(response.body(), id);
        } catch (CveilAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new CveilAuthException("Token refresh failed: " + e.getMessage());
        }
    }

    /**
     * Revoke a refresh token (OIDC logout).
     *
     * @param id ignored — targets single realm
     * @param refreshToken the refresh token to revoke
     */
    public void logout(String id, String refreshToken) {
        String realmId = config.cveilRealmName();
        try {
            String clientSecret = resolveClientSecret();
            String logoutUrl =
                    config.serverUrl() + "/realms/" + realmId + "/protocol/openid-connect/logout";

            String formBody =
                    "client_id="
                            + urlEncode(config.cveilClientId())
                            + "&client_secret="
                            + urlEncode(clientSecret)
                            + "&refresh_token="
                            + urlEncode(refreshToken);

            post(logoutUrl, formBody);
        } catch (Exception e) {
            LOG.warn("Logout failed: {}", e.getMessage());
        }
    }

    // -- Internal helpers -----------------------------------------------------

    private String tokenUrl(String realmId) {
        return config.serverUrl() + "/realms/" + realmId + "/protocol/openid-connect/token";
    }

    private HttpResponse<String> post(String url, String formBody) throws Exception {
        return post(url, formBody, null);
    }

    private HttpResponse<String> post(String url, String formBody, String dpopProof)
            throws Exception {
        var builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(HTTP_TIMEOUT);

        if (dpopProof != null && !dpopProof.isBlank()) {
            builder.header("DPoP", dpopProof);
        }

        return httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(formBody)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Resolve the client secret (single realm — cached after first resolution). */
    private String resolveClientSecret() {
        if (cachedClientSecret != null) return cachedClientSecret;

        if (config.cveilClientSecret() != null) {
            cachedClientSecret = config.cveilClientSecret();
            return cachedClientSecret;
        }

        // Dynamic resolution via Admin API
        String realmId = config.cveilRealmName();
        try {
            RealmResource realm = adminClient.realm(realmId);
            var clients = realm.clients().findByClientId(config.cveilClientId());
            if (!clients.isEmpty()) {
                String internalId = clients.get(0).getId();
                String secret = realm.clients().get(internalId).getSecret().getValue();
                if (secret != null && !secret.isBlank()) {
                    cachedClientSecret = secret;
                    return secret;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve client secret for realm '{}': {}", realmId, e.getMessage());
        }
        throw new CveilAuthException("No client secret available for realm '" + realmId + "'");
    }

    private AuthResult parseTokenResponse(String json, String tenantId) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String accessToken =
                    root.has("access_token") ? root.get("access_token").asText() : null;
            String refreshTokenVal =
                    root.has("refresh_token") ? root.get("refresh_token").asText() : null;
            long expiresIn = root.has("expires_in") ? root.get("expires_in").asLong() : 900;
            String tokenType = root.has("token_type") ? root.get("token_type").asText() : "Bearer";

            if (accessToken == null) {
                throw new CveilAuthException("Keycloak returned no access_token");
            }

            String realmId = config.cveilRealmName();
            String jwksUrl =
                    config.serverUrl() + "/realms/" + realmId + "/protocol/openid-connect/certs";
            IdentityContext identity = jwksVerifier.verify(jwksUrl, accessToken, tenantId);

            if ("DPoP".equalsIgnoreCase(tokenType)) {
                return AuthResult.dpop(accessToken, refreshTokenVal, expiresIn, identity);
            }
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
