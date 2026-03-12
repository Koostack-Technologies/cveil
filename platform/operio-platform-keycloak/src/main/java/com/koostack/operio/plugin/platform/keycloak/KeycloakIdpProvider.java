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
 * Keycloak-backed identity provider — single realm with Keycloak Organizations for tenancy.
 *
 * <p>All users exist in a single Keycloak realm ({@code operio} by default). Tenant isolation is
 * achieved via Keycloak Organizations — each organization is a tenant. The {@code tenantId}
 * parameter on methods is used for organization scoping and context, but authentication always
 * targets the single shared realm.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is thread-safe. The Keycloak admin client is shared across threads.
 */
public class KeycloakIdpProvider implements IdpProvider<KeycloakConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakIdpProvider.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private KeycloakConfig config;
    private Keycloak adminClient;
    private KeycloakRealmProvisioner realmProvisioner;
    private JwksTokenVerifier jwksVerifier;
    private EmailService emailService;
    private HttpClient httpClient;
    private String clientSecret;

    public KeycloakIdpProvider() {}

    public KeycloakIdpProvider(KeycloakConfig config) {
        configure(config);
    }

    public KeycloakIdpProvider withEmailService(EmailService emailService) {
        this.emailService = emailService;
        return this;
    }

    /** Exposes the admin client for organization management. */
    public Keycloak getAdminClient() {
        return adminClient;
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
        this.realmProvisioner =
                new KeycloakRealmProvisioner(
                        adminClient, config.operioClientId(), config.serverUrl());
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
    // Authentication (all against single realm)
    // -------------------------------------------------------------------

    @Override
    public AuthResult authenticate(String tenantId, String email, String password) {
        String realm = config.operioRealmName();
        try {
            String secret = resolveClientSecret();
            String tokenUrl =
                    config.serverUrl() + "/realms/" + realm + "/protocol/openid-connect/token";

            String formBody =
                    "grant_type=password"
                            + "&client_id="
                            + urlEncode(config.operioClientId())
                            + "&client_secret="
                            + urlEncode(secret)
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
                        "Authentication failed for email '{}': HTTP {}",
                        email,
                        response.statusCode());
                throw new OperioAuthException("Invalid email or password");
            }

            return parseTokenResponse(response.body(), tenantId);
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            LOG.debug("Authentication failed for email '{}': {}", email, e.getMessage());
            throw new OperioAuthException("Invalid email or password");
        }
    }

    @Override
    public AuthResult refreshToken(String tenantId, String refreshToken) {
        String realm = config.operioRealmName();
        try {
            String secret = resolveClientSecret();
            String tokenUrl =
                    config.serverUrl() + "/realms/" + realm + "/protocol/openid-connect/token";

            String formBody =
                    "grant_type=refresh_token"
                            + "&client_id="
                            + urlEncode(config.operioClientId())
                            + "&client_secret="
                            + urlEncode(secret)
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
                throw new OperioAuthException("Invalid or expired refresh token");
            }

            return parseTokenResponse(response.body(), tenantId);
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioAuthException("Invalid or expired refresh token");
        }
    }

    @Override
    public void logout(String tenantId, String refreshToken) {
        String realm = config.operioRealmName();
        try {
            String secret = resolveClientSecret();
            String logoutUrl =
                    config.serverUrl() + "/realms/" + realm + "/protocol/openid-connect/logout";

            String formBody =
                    "client_id="
                            + urlEncode(config.operioClientId())
                            + "&client_secret="
                            + urlEncode(secret)
                            + "&refresh_token="
                            + urlEncode(refreshToken);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(logoutUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(HTTP_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOG.warn("Logout failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // User Management (single realm)
    // -------------------------------------------------------------------

    @Override
    public IdpUser registerUser(String tenantId, String email, String password, String fullName) {
        String realm = config.operioRealmName();
        UsersResource usersResource = adminClient.realm(realm).users();

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

            String locationHeader = response.getHeaderString("Location");
            if (locationHeader == null || !locationHeader.contains("/")) {
                throw new OperioServiceException(
                        "Keycloak did not return a Location header for the created user");
            }
            String userId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);

            LOG.info("User registered: userId={}, email={}", userId, email);

            try {
                adminClient
                        .realm(realm)
                        .users()
                        .get(userId)
                        .executeActionsEmail(List.of("VERIFY_EMAIL"));
            } catch (Exception e) {
                LOG.warn(
                        "Failed to send verification email for user '{}': {}",
                        userId,
                        e.getMessage());
            }

            return new IdpUser(email, email, fullName, "unverified", Instant.now());
        } finally {
            response.close();
        }
    }

    @Override
    public void verifyEmail(String tenantId, String userId, String verificationToken) {
        String realm = config.operioRealmName();
        UserResource userResource = adminClient.realm(realm).users().get(userId);
        UserRepresentation user = userResource.toRepresentation();
        user.setEmailVerified(true);
        userResource.update(user);
        LOG.info("Email verified for user '{}'", userId);
    }

    @Override
    public void initiatePasswordReset(String tenantId, String email) {
        String realm = config.operioRealmName();
        List<UserRepresentation> users =
                adminClient.realm(realm).users().searchByEmail(email, true);

        if (users.isEmpty()) {
            LOG.debug("Password reset requested for non-existent email");
            return;
        }

        String userId = users.get(0).getId();
        adminClient
                .realm(realm)
                .users()
                .get(userId)
                .executeActionsEmail(List.of("UPDATE_PASSWORD"));
        LOG.info("Password reset initiated for email '{}'", email);
    }

    @Override
    public void completePasswordReset(String tenantId, String resetToken, String newPassword) {
        String realm = config.operioRealmName();
        try {
            UserResource userResource = adminClient.realm(realm).users().get(resetToken);
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(newPassword);
            credential.setTemporary(false);
            userResource.resetPassword(credential);
            LOG.info("Password reset completed for user '{}'", resetToken);
        } catch (Exception e) {
            throw new OperioAuthException("Invalid or expired reset token");
        }
    }

    // -------------------------------------------------------------------
    // Token Verification (hot path — single JWKS endpoint)
    // -------------------------------------------------------------------

    @Override
    public IdentityContext verifyToken(String tenantId, String accessToken) {
        return jwksVerifier.verify(jwksUrl(), accessToken, tenantId);
    }

    // -------------------------------------------------------------------
    // Realm Lifecycle — single realm bootstrap
    // -------------------------------------------------------------------

    @Override
    public void provisionTenant(String tenantId, String displayName) {
        // No-op: tenants are Keycloak Organizations, not realms.
        // Organization creation is handled by the command orchestrator.
        LOG.debug("provisionTenant({}) is a no-op — use KeycloakOrganizationService", tenantId);
    }

    @Override
    public void deprovisionTenant(String tenantId) {
        // No-op: tenant deprovision deletes the organization, not a realm.
        LOG.debug("deprovisionTenant({}) is a no-op — use KeycloakOrganizationService", tenantId);
    }

    /**
     * Ensure the single Operio realm exists with all required configuration. Called at application
     * startup.
     *
     * @param rootEmail the root admin email
     * @param rootPassword the root admin password
     * @return the client secret for the operio-backend client
     */
    public String ensureOperioRealm(String rootEmail, String rootPassword) {
        var result =
                realmProvisioner.ensurePlatformRealm(
                        config.operioRealmName(), rootEmail, rootPassword);
        this.clientSecret = result.clientSecret();
        LOG.info("Operio realm '{}' ensured, client secret cached", config.operioRealmName());
        return result.clientSecret();
    }

    // -------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------

    private String jwksUrl() {
        return config.serverUrl()
                + "/realms/"
                + config.operioRealmName()
                + "/protocol/openid-connect/certs";
    }

    private String resolveClientSecret() {
        if (clientSecret != null) {
            return clientSecret;
        }

        if (config.operioClientSecret() != null) {
            clientSecret = config.operioClientSecret();
            return clientSecret;
        }

        // Fetch from Keycloak admin API
        try {
            RealmResource realm = adminClient.realm(config.operioRealmName());
            var clients = realm.clients().findByClientId(config.operioClientId());
            if (!clients.isEmpty()) {
                String internalId = clients.get(0).getId();
                String secret = realm.clients().get(internalId).getSecret().getValue();
                if (secret != null && !secret.isBlank()) {
                    clientSecret = secret;
                    return secret;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve client secret: {}", e.getMessage());
        }

        throw new OperioAuthException(
                "No client secret available for realm '" + config.operioRealmName() + "'");
    }

    private AuthResult parseTokenResponse(String json, String tenantId) {
        String accessToken = extractJsonStringValue(json, "access_token");
        String refreshToken = extractJsonStringValue(json, "refresh_token");
        String expiresInStr = extractJsonNumberValue(json, "expires_in");

        if (accessToken == null) {
            throw new OperioAuthException("Keycloak returned no access_token");
        }

        long expiresIn = expiresInStr != null ? Long.parseLong(expiresInStr) : 900;

        IdentityContext identity = jwksVerifier.verify(jwksUrl(), accessToken, tenantId);

        return AuthResult.bearer(accessToken, refreshToken, expiresIn, identity);
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
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

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
