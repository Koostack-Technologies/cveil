package com.koostack.operio.plugin.platform.keycloak;

import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.EmailService;
import com.koostack.operio.core.platform.iam.IdpProvider;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-backed identity provider — the platform default.
 *
 * <p>Uses the Keycloak Admin REST API for user management and realm lifecycle, and the token
 * endpoint for authentication. JWT verification is delegated to {@link JwksTokenVerifier} using the
 * realm's JWKS endpoint.
 *
 * <h2>Realm Mapping</h2>
 *
 * <p>Each Operio tenant maps to a dedicated Keycloak realm. The realm name equals the tenant ID.
 * Provisioning creates the realm, a confidential client, and default roles.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is thread-safe. The Keycloak admin client is shared across threads; per-tenant
 * client secrets are cached in a {@link ConcurrentHashMap}.
 */
public class KeycloakIdpProvider implements IdpProvider<KeycloakConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakIdpProvider.class);

    /** Timeout for direct HTTP calls to Keycloak token/logout endpoints. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private KeycloakConfig config;
    private Keycloak adminClient;
    private KeycloakRealmProvisioner realmProvisioner;
    private JwksTokenVerifier jwksVerifier;
    private EmailService emailService;

    /** JDK HttpClient for direct token endpoint calls (refresh, logout). */
    private HttpClient httpClient;

    // Per-tenant client secrets (populated during provisioning or lazily)
    private final ConcurrentHashMap<String, String> tenantClientSecrets = new ConcurrentHashMap<>();

    public KeycloakIdpProvider() {}

    public KeycloakIdpProvider(KeycloakConfig config) {
        configure(config);
    }

    /**
     * Set the email service used for verification and password reset emails.
     *
     * @param emailService the email delivery service
     * @return this provider for chaining
     */
    public KeycloakIdpProvider withEmailService(EmailService emailService) {
        this.emailService = emailService;
        return this;
    }

    // -------------------------------------------------------------------
    // Plugin SPI
    // -------------------------------------------------------------------

    @Override
    public String getName() {
        return "keycloak";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<KeycloakConfig> getConfigurationType() {
        return KeycloakConfig.class;
    }

    @Override
    public void configure(KeycloakConfig config) {
        this.config = config;
        this.adminClient =
                KeycloakBuilder.builder()
                        .serverUrl(config.serverUrl())
                        .realm(config.adminRealm())
                        .clientId(config.adminClientId())
                        .username(config.adminUsername())
                        .password(config.adminPassword())
                        .build();
        this.realmProvisioner = new KeycloakRealmProvisioner(adminClient, config.operioClientId());
        this.jwksVerifier = new JwksTokenVerifier();
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    @Override
    public boolean isReady() {
        if (adminClient == null) {
            return false;
        }
        try {
            adminClient.realm(config.adminRealm()).toRepresentation();
            return true;
        } catch (Exception e) {
            LOG.debug("Keycloak health check failed: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------

    @Override
    public AuthResult authenticate(String tenantId, String email, String password) {
        try {
            String clientSecret = resolveClientSecret(tenantId);
            String tokenUrl =
                    config.serverUrl() + "/realms/" + tenantId + "/protocol/openid-connect/token";

            String formBody =
                    "grant_type=password"
                            + "&client_id="
                            + urlEncode(config.operioClientId())
                            + "&client_secret="
                            + urlEncode(clientSecret)
                            + "&username="
                            + urlEncode(email)
                            + "&password="
                            + urlEncode(password);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(tokenUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.debug(
                        "Authentication failed for tenant '{}', email '{}': HTTP {}",
                        tenantId,
                        email,
                        response.statusCode());
                throw new OperioAuthException("Invalid email or password");
            }

            return parseTokenResponse(response.body(), tenantId);
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            LOG.debug(
                    "Authentication failed for tenant '{}', email '{}': {}",
                    tenantId,
                    email,
                    e.getMessage());
            throw new OperioAuthException("Invalid email or password");
        }
    }

    @Override
    public AuthResult refreshToken(String tenantId, String refreshToken) {
        try {
            String clientSecret = resolveClientSecret(tenantId);
            String tokenUrl =
                    config.serverUrl() + "/realms/" + tenantId + "/protocol/openid-connect/token";

            String formBody =
                    "grant_type=refresh_token"
                            + "&client_id="
                            + urlEncode(config.operioClientId())
                            + "&client_secret="
                            + urlEncode(clientSecret)
                            + "&refresh_token="
                            + urlEncode(refreshToken);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(tokenUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.debug(
                        "Token refresh failed for tenant '{}': HTTP {}",
                        tenantId,
                        response.statusCode());
                throw new OperioAuthException("Invalid or expired refresh token");
            }

            return parseTokenResponse(response.body(), tenantId);
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            LOG.debug("Token refresh failed for tenant '{}': {}", tenantId, e.getMessage());
            throw new OperioAuthException("Invalid or expired refresh token");
        }
    }

    @Override
    public void logout(String tenantId, String refreshToken) {
        try {
            String clientSecret = resolveClientSecret(tenantId);
            String logoutUrl =
                    config.serverUrl() + "/realms/" + tenantId + "/protocol/openid-connect/logout";

            String formBody =
                    "client_id="
                            + urlEncode(config.operioClientId())
                            + "&client_secret="
                            + urlEncode(clientSecret)
                            + "&refresh_token="
                            + urlEncode(refreshToken);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(logoutUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Refresh token revoked for tenant '{}'", tenantId);
            } else {
                // Log but don't fail — the token may already be expired/revoked
                LOG.warn(
                        "Logout returned HTTP {} for tenant '{}': {}",
                        response.statusCode(),
                        tenantId,
                        response.body());
            }
        } catch (Exception e) {
            LOG.warn("Logout failed for tenant '{}': {}", tenantId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // User Management
    // -------------------------------------------------------------------

    @Override
    public IdpUser registerUser(String tenantId, String email, String password, String fullName) {
        UsersResource usersResource = adminClient.realm(tenantId).users();

        // Check if user already exists
        List<UserRepresentation> existing = usersResource.searchByEmail(email, true);
        if (!existing.isEmpty()) {
            throw new OperioServiceException("A user with email '" + email + "' already exists");
        }

        UserRepresentation user = new UserRepresentation();
        user.setEmail(email);
        user.setUsername(email);
        user.setFirstName(parseName(fullName, true));
        user.setLastName(parseName(fullName, false));
        user.setEnabled(true);
        user.setEmailVerified(false);

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));

        Response response = usersResource.create(user);
        try {
            if (response.getStatus() != 201) {
                String body = response.hasEntity() ? response.readEntity(String.class) : "";
                throw new OperioServiceException(
                        "Failed to create user in Keycloak: HTTP "
                                + response.getStatus()
                                + (body.isEmpty() ? "" : " — " + body));
            }

            // Extract user ID from Location header
            String locationHeader = response.getHeaderString("Location");
            if (locationHeader == null || !locationHeader.contains("/")) {
                throw new OperioServiceException(
                        "Keycloak did not return a Location header for the created user");
            }
            String userId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);

            LOG.info(
                    "User registered in tenant '{}': userId={}, email={}", tenantId, userId, email);

            // Trigger Keycloak's built-in email verification flow
            try {
                adminClient
                        .realm(tenantId)
                        .users()
                        .get(userId)
                        .executeActionsEmail(List.of("VERIFY_EMAIL"));
            } catch (Exception e) {
                LOG.warn(
                        "Failed to send verification email for user '{}' in tenant '{}': {}",
                        userId,
                        tenantId,
                        e.getMessage());
            }

            return new IdpUser(email, email, fullName, "unverified", Instant.now());
        } finally {
            response.close();
        }
    }

    @Override
    public void verifyEmail(String tenantId, String userId, String verificationToken) {
        UserResource userResource = adminClient.realm(tenantId).users().get(userId);
        UserRepresentation user = userResource.toRepresentation();
        user.setEmailVerified(true);
        userResource.update(user);
        LOG.info("Email verified for user '{}' in tenant '{}'", userId, tenantId);
    }

    @Override
    public void initiatePasswordReset(String tenantId, String email) {
        List<UserRepresentation> users =
                adminClient.realm(tenantId).users().searchByEmail(email, true);

        if (users.isEmpty()) {
            // Don't reveal whether the email exists
            LOG.debug("Password reset requested for non-existent email in tenant '{}'", tenantId);
            return;
        }

        String userId = users.get(0).getId();
        adminClient
                .realm(tenantId)
                .users()
                .get(userId)
                .executeActionsEmail(List.of("UPDATE_PASSWORD"));

        LOG.info("Password reset initiated for email '{}' in tenant '{}'", email, tenantId);
    }

    @Override
    public void completePasswordReset(String tenantId, String resetToken, String newPassword) {
        // Keycloak handles password reset via its own action-token flow.
        // When Operio proxies the reset, we look up the user by the reset
        // token (stored in the verification_token table at the REST layer),
        // then set the new password via the admin API.
        //
        // The resetToken here is actually the Keycloak userId — the
        // AuthResource layer resolves the opaque reset token to a userId
        // before calling this method.
        try {
            UserResource userResource = adminClient.realm(tenantId).users().get(resetToken);
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(newPassword);
            credential.setTemporary(false);
            userResource.resetPassword(credential);
            LOG.info("Password reset completed for user '{}' in tenant '{}'", resetToken, tenantId);
        } catch (Exception e) {
            LOG.debug("Password reset failed for tenant '{}': {}", tenantId, e.getMessage());
            throw new OperioAuthException("Invalid or expired reset token");
        }
    }

    // -------------------------------------------------------------------
    // Token Verification (hot path)
    // -------------------------------------------------------------------

    @Override
    public IdentityContext verifyToken(String tenantId, String accessToken) {
        return jwksVerifier.verify(jwksUrl(tenantId), accessToken, tenantId);
    }

    // -------------------------------------------------------------------
    // Realm / Tenant Lifecycle
    // -------------------------------------------------------------------

    @Override
    public void provisionTenant(String tenantId, String displayName) {
        String clientSecret = realmProvisioner.provisionRealm(tenantId, displayName);
        tenantClientSecrets.put(tenantId, clientSecret);
        LOG.info("Tenant '{}' provisioned in Keycloak, client secret cached", tenantId);
    }

    @Override
    public void deprovisionTenant(String tenantId) {
        realmProvisioner.deprovisionRealm(tenantId);
        tenantClientSecrets.remove(tenantId);
        jwksVerifier.evict(jwksUrl(tenantId));
        LOG.info("Tenant '{}' deprovisioned from Keycloak", tenantId);
    }

    // -------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------

    private String jwksUrl(String tenantId) {
        return config.serverUrl() + "/realms/" + tenantId + "/protocol/openid-connect/certs";
    }

    private String resolveClientSecret(String tenantId) {
        String cached = tenantClientSecrets.get(tenantId);
        if (cached != null) {
            return cached;
        }

        if (config.operioClientSecret() != null) {
            tenantClientSecrets.put(tenantId, config.operioClientSecret());
            return config.operioClientSecret();
        }

        // Fetch from Keycloak admin API
        try {
            RealmResource realm = adminClient.realm(tenantId);
            var clients = realm.clients().findByClientId(config.operioClientId());
            if (!clients.isEmpty()) {
                String internalId = clients.get(0).getId();
                String secret = realm.clients().get(internalId).getSecret().getValue();
                if (secret != null && !secret.isBlank()) {
                    tenantClientSecrets.put(tenantId, secret);
                    return secret;
                }
            }
        } catch (Exception e) {
            LOG.warn(
                    "Failed to resolve client secret for tenant '{}': {}",
                    tenantId,
                    e.getMessage());
        }

        throw new OperioAuthException(
                "No client secret available for tenant '"
                        + tenantId
                        + "'. "
                        + "Was the realm provisioned via provisionTenant()?");
    }

    /**
     * Parse the JSON token response from Keycloak's token endpoint. Uses minimal JSON extraction to
     * avoid adding a Jackson dependency.
     */
    private AuthResult parseTokenResponse(String json, String tenantId) {
        String accessToken = extractJsonStringValue(json, "access_token");
        String refreshToken = extractJsonStringValue(json, "refresh_token");
        String expiresInStr = extractJsonNumberValue(json, "expires_in");

        if (accessToken == null) {
            throw new OperioAuthException("Keycloak returned no access_token");
        }

        long expiresIn = expiresInStr != null ? Long.parseLong(expiresInStr) : 900;

        IdentityContext identity = jwksVerifier.verify(jwksUrl(tenantId), accessToken, tenantId);

        return AuthResult.bearer(accessToken, refreshToken, expiresIn, identity);
    }

    /** Extract a JSON string value by key. Handles escaped quotes within values. */
    private static String extractJsonStringValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return null;

        int quoteStart = json.indexOf('"', colonIndex + 1);
        if (quoteStart < 0) return null;

        // Handle escaped quotes
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

    /** Extract a JSON numeric value by key (unquoted). */
    private static String extractJsonNumberValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return null;

        // Skip whitespace after colon
        int start = colonIndex + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        // Read digits
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
            end++;
        }
        return start < end ? json.substring(start, end) : null;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String parseName(String fullName, boolean first) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String[] parts = fullName.trim().split("\\s+", 2);
        if (first) {
            return parts[0];
        }
        return parts.length > 1 ? parts[1] : "";
    }
}
