package com.koostack.operio.plugin.platform.keycloak;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.*;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link KeycloakIdpProvider}.
 *
 * <p>Uses Mockito to mock the Keycloak admin client, JDK HttpClient, and JWKS verifier. Fields are
 * injected via reflection since the provider creates its dependencies internally in {@code
 * configure()}.
 */
@DisplayName("KeycloakIdpProvider")
class KeycloakIdpProviderTest {

    private static final String TENANT_ID = "test-tenant";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String SERVER_URL = "http://keycloak:8180";
    private static final String JWKS_URL =
            SERVER_URL + "/realms/" + TENANT_ID + "/protocol/openid-connect/certs";

    private Keycloak adminClient;
    private RealmResource realmResource;
    private UsersResource usersResource;
    private HttpClient httpClient;
    private JwksTokenVerifier jwksVerifier;
    private KeycloakRealmProvisioner realmProvisioner;
    private KeycloakConfig config;
    private KeycloakIdpProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        adminClient = mock(Keycloak.class);
        realmResource = mock(RealmResource.class);
        usersResource = mock(UsersResource.class);
        httpClient = mock(HttpClient.class);
        jwksVerifier = mock(JwksTokenVerifier.class);
        realmProvisioner = mock(KeycloakRealmProvisioner.class);

        config =
                new KeycloakConfig(
                        SERVER_URL,
                        "master",
                        "admin-cli",
                        "admin",
                        "admin",
                        "operio-backend",
                        null);

        provider = new KeycloakIdpProvider();
        setField(provider, "config", config);
        setField(provider, "adminClient", adminClient);
        setField(provider, "httpClient", httpClient);
        setField(provider, "jwksVerifier", jwksVerifier);
        setField(provider, "realmProvisioner", realmProvisioner);

        // Pre-populate client secret so resolveClientSecret doesn't hit admin API
        getSecretCache(provider).put(TENANT_ID, CLIENT_SECRET);

        // Default admin client routing
        when(adminClient.realm(TENANT_ID)).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
    }

    // -----------------------------------------------------------------------
    // Plugin SPI metadata
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plugin SPI")
    class PluginSpi {

        @Test
        @DisplayName("getName() returns 'keycloak'")
        void name() {
            assertEquals("keycloak", provider.getName());
        }

        @Test
        @DisplayName("getPriority() returns 100")
        void priority() {
            assertEquals(100, provider.getPriority());
        }

        @Test
        @DisplayName("getConfigurationType() returns KeycloakConfig.class")
        void configurationType() {
            assertEquals(KeycloakConfig.class, provider.getConfigurationType());
        }
    }

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Authentication")
    class Authentication {

        private static final String TOKEN_JSON =
                "{\"access_token\":\"mock-access-token\","
                        + "\"refresh_token\":\"mock-refresh-token\","
                        + "\"expires_in\":900,"
                        + "\"token_type\":\"Bearer\"}";

        private final IdentityContext mockIdentity =
                new IdentityContext(
                        "user-123",
                        "mock-access-token",
                        "user@test.com",
                        Set.of("consent:read"),
                        Map.of(),
                        TENANT_ID,
                        "keycloak");

        @Test
        @DisplayName("returns AuthResult on successful authentication")
        @SuppressWarnings("unchecked")
        void authenticateSuccess() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(TOKEN_JSON);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);
            when(jwksVerifier.verify(JWKS_URL, "mock-access-token", TENANT_ID))
                    .thenReturn(mockIdentity);

            AuthResult result = provider.authenticate(TENANT_ID, "user@test.com", "password");

            assertEquals("mock-access-token", result.accessToken());
            assertEquals("mock-refresh-token", result.refreshToken());
            assertEquals(900, result.expiresIn());
            assertEquals("Bearer", result.tokenType());
            assertSame(mockIdentity, result.identity());
        }

        @Test
        @DisplayName("sends POST to correct token endpoint URL")
        @SuppressWarnings("unchecked")
        void sendsToCorrectUrl() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(TOKEN_JSON);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);
            when(jwksVerifier.verify(anyString(), anyString(), anyString()))
                    .thenReturn(mockIdentity);

            provider.authenticate(TENANT_ID, "user@test.com", "password");

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any());
            HttpRequest request = captor.getValue();

            assertEquals(
                    SERVER_URL + "/realms/" + TENANT_ID + "/protocol/openid-connect/token",
                    request.uri().toString());
            assertEquals("POST", request.method());
        }

        @Test
        @DisplayName("throws OperioAuthException on HTTP 401")
        @SuppressWarnings("unchecked")
        void throwsOnUnauthorized() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(401);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            OperioAuthException ex =
                    assertThrows(
                            OperioAuthException.class,
                            () ->
                                    provider.authenticate(
                                            TENANT_ID, "user@test.com", "wrong-password"));
            assertTrue(ex.getMessage().contains("Invalid email or password"));
        }

        @Test
        @DisplayName("throws OperioAuthException on network error")
        @SuppressWarnings("unchecked")
        void throwsOnNetworkError() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection refused"));

            assertThrows(
                    OperioAuthException.class,
                    () -> provider.authenticate(TENANT_ID, "user@test.com", "password"));
        }

        @Test
        @DisplayName("throws OperioAuthException when response has no access_token")
        @SuppressWarnings("unchecked")
        void throwsOnMissingAccessToken() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"refresh_token\":\"rt\",\"expires_in\":900}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            assertThrows(
                    OperioAuthException.class,
                    () -> provider.authenticate(TENANT_ID, "user@test.com", "password"));
        }

        @Test
        @DisplayName("uses default expires_in of 900 when not in response")
        @SuppressWarnings("unchecked")
        void defaultsExpiresIn() throws Exception {
            String jsonNoExpires = "{\"access_token\":\"at\",\"refresh_token\":\"rt\"}";
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(jsonNoExpires);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);
            when(jwksVerifier.verify(anyString(), eq("at"), eq(TENANT_ID)))
                    .thenReturn(mockIdentity);

            AuthResult result = provider.authenticate(TENANT_ID, "user@test.com", "password");

            assertEquals(900, result.expiresIn());
        }
    }

    // -----------------------------------------------------------------------
    // Token refresh
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Token refresh")
    class TokenRefresh {

        private static final String REFRESH_JSON =
                "{\"access_token\":\"new-at\","
                        + "\"refresh_token\":\"new-rt\","
                        + "\"expires_in\":900}";

        private final IdentityContext mockIdentity =
                new IdentityContext(
                        "user-123",
                        "new-at",
                        "user@test.com",
                        Set.of(),
                        Map.of(),
                        TENANT_ID,
                        "keycloak");

        @Test
        @DisplayName("returns new AuthResult on successful refresh")
        @SuppressWarnings("unchecked")
        void refreshSuccess() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(REFRESH_JSON);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);
            when(jwksVerifier.verify(JWKS_URL, "new-at", TENANT_ID)).thenReturn(mockIdentity);

            AuthResult result = provider.refreshToken(TENANT_ID, "old-refresh-token");

            assertEquals("new-at", result.accessToken());
            assertEquals("new-rt", result.refreshToken());
        }

        @Test
        @DisplayName("throws OperioAuthException on invalid refresh token")
        @SuppressWarnings("unchecked")
        void throwsOnInvalidRefreshToken() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(400);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            OperioAuthException ex =
                    assertThrows(
                            OperioAuthException.class,
                            () -> provider.refreshToken(TENANT_ID, "invalid-token"));
            assertTrue(ex.getMessage().contains("refresh token"));
        }
    }

    // -----------------------------------------------------------------------
    // Logout
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Logout")
    class Logout {

        @Test
        @DisplayName("sends POST to Keycloak logout endpoint")
        @SuppressWarnings("unchecked")
        void logoutSendsRequest() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(204);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            provider.logout(TENANT_ID, "refresh-token");

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any());
            assertTrue(captor.getValue().uri().toString().contains("/logout"));
        }

        @Test
        @DisplayName("does not throw when logout returns non-success status")
        @SuppressWarnings("unchecked")
        void doesNotThrowOnError() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(400);
            when(mockResponse.body()).thenReturn("Bad Request");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            // Should not throw — failures are logged but swallowed
            assertDoesNotThrow(() -> provider.logout(TENANT_ID, "already-revoked-token"));
        }

        @Test
        @DisplayName("does not throw when HTTP call fails")
        @SuppressWarnings("unchecked")
        void doesNotThrowOnNetworkError() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection refused"));

            assertDoesNotThrow(() -> provider.logout(TENANT_ID, "refresh-token"));
        }
    }

    // -----------------------------------------------------------------------
    // User registration
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("User registration")
    class UserRegistration {

        @Test
        @DisplayName("registers a new user and returns IdpUser")
        void registerNewUser() {
            when(usersResource.searchByEmail("user@test.com", true)).thenReturn(List.of());

            Response mockResp = mock(Response.class);
            when(mockResp.getStatus()).thenReturn(201);
            when(mockResp.getHeaderString("Location"))
                    .thenReturn("http://kc/admin/realms/test-tenant/users/new-user-123");
            when(usersResource.create(any(UserRepresentation.class))).thenReturn(mockResp);

            UserResource userResource = mock(UserResource.class);
            when(usersResource.get("new-user-123")).thenReturn(userResource);

            IdpUser result =
                    provider.registerUser(TENANT_ID, "user@test.com", "password", "John Doe");

            assertEquals("user@test.com", result.username());
            assertEquals("user@test.com", result.email());
            assertEquals("John Doe", result.fullName());
            assertEquals("unverified", result.status());
            assertNotNull(result.createdAt());
        }

        @Test
        @DisplayName("parses first and last name from full name")
        void parsesFullName() {
            when(usersResource.searchByEmail(anyString(), eq(true))).thenReturn(List.of());

            Response mockResp = mock(Response.class);
            when(mockResp.getStatus()).thenReturn(201);
            when(mockResp.getHeaderString("Location")).thenReturn("http://kc/users/u1");
            when(usersResource.create(any(UserRepresentation.class))).thenReturn(mockResp);
            when(usersResource.get("u1")).thenReturn(mock(UserResource.class));

            provider.registerUser(TENANT_ID, "a@b.com", "pw", "Jane Marie Smith");

            ArgumentCaptor<UserRepresentation> captor =
                    ArgumentCaptor.forClass(UserRepresentation.class);
            verify(usersResource).create(captor.capture());

            assertEquals("Jane", captor.getValue().getFirstName());
            assertEquals("Marie Smith", captor.getValue().getLastName());
        }

        @Test
        @DisplayName("handles single-word full name")
        void handlesSingleWordName() {
            when(usersResource.searchByEmail(anyString(), eq(true))).thenReturn(List.of());

            Response mockResp = mock(Response.class);
            when(mockResp.getStatus()).thenReturn(201);
            when(mockResp.getHeaderString("Location")).thenReturn("http://kc/users/u1");
            when(usersResource.create(any(UserRepresentation.class))).thenReturn(mockResp);
            when(usersResource.get("u1")).thenReturn(mock(UserResource.class));

            provider.registerUser(TENANT_ID, "a@b.com", "pw", "Madonna");

            ArgumentCaptor<UserRepresentation> captor =
                    ArgumentCaptor.forClass(UserRepresentation.class);
            verify(usersResource).create(captor.capture());

            assertEquals("Madonna", captor.getValue().getFirstName());
            assertEquals("", captor.getValue().getLastName());
        }

        @Test
        @DisplayName("throws OperioServiceException when email already exists")
        void throwsOnDuplicateEmail() {
            UserRepresentation existing = new UserRepresentation();
            existing.setEmail("user@test.com");
            when(usersResource.searchByEmail("user@test.com", true)).thenReturn(List.of(existing));

            OperioServiceException ex =
                    assertThrows(
                            OperioServiceException.class,
                            () ->
                                    provider.registerUser(
                                            TENANT_ID, "user@test.com", "password", "John Doe"));
            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("throws OperioServiceException when Keycloak returns non-201")
        void throwsOnKeycloakError() {
            when(usersResource.searchByEmail(anyString(), eq(true))).thenReturn(List.of());

            Response mockResp = mock(Response.class);
            when(mockResp.getStatus()).thenReturn(409);
            when(mockResp.hasEntity()).thenReturn(false);
            when(usersResource.create(any(UserRepresentation.class))).thenReturn(mockResp);

            assertThrows(
                    OperioServiceException.class,
                    () ->
                            provider.registerUser(
                                    TENANT_ID, "user@test.com", "password", "John Doe"));
        }

        @Test
        @DisplayName("triggers verification email after registration")
        void triggersVerificationEmail() {
            when(usersResource.searchByEmail(anyString(), eq(true))).thenReturn(List.of());

            Response mockResp = mock(Response.class);
            when(mockResp.getStatus()).thenReturn(201);
            when(mockResp.getHeaderString("Location")).thenReturn("http://kc/users/user-99");
            when(usersResource.create(any(UserRepresentation.class))).thenReturn(mockResp);

            UserResource userResource = mock(UserResource.class);
            when(usersResource.get("user-99")).thenReturn(userResource);

            provider.registerUser(TENANT_ID, "a@b.com", "pw", "Test User");

            verify(userResource).executeActionsEmail(List.of("VERIFY_EMAIL"));
        }
    }

    // -----------------------------------------------------------------------
    // Email verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Email verification")
    class EmailVerification {

        @Test
        @DisplayName("sets emailVerified=true on the user")
        void verifiesEmail() {
            UserResource userResource = mock(UserResource.class);
            UserRepresentation userRep = new UserRepresentation();
            userRep.setEmailVerified(false);
            when(usersResource.get("user-123")).thenReturn(userResource);
            when(userResource.toRepresentation()).thenReturn(userRep);

            provider.verifyEmail(TENANT_ID, "user-123", "verification-token");

            assertTrue(userRep.isEmailVerified());
            verify(userResource).update(userRep);
        }
    }

    // -----------------------------------------------------------------------
    // Password reset
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Password reset")
    class PasswordReset {

        @Test
        @DisplayName("initiates password reset for existing user")
        void initiateForExistingUser() {
            UserRepresentation user = new UserRepresentation();
            user.setId("user-123");
            when(usersResource.searchByEmail("user@test.com", true)).thenReturn(List.of(user));

            UserResource userResource = mock(UserResource.class);
            when(usersResource.get("user-123")).thenReturn(userResource);

            provider.initiatePasswordReset(TENANT_ID, "user@test.com");

            verify(userResource).executeActionsEmail(List.of("UPDATE_PASSWORD"));
        }

        @Test
        @DisplayName("silently no-ops for non-existent email (prevents enumeration)")
        void noOpForNonExistentEmail() {
            when(usersResource.searchByEmail("unknown@test.com", true)).thenReturn(List.of());

            assertDoesNotThrow(() -> provider.initiatePasswordReset(TENANT_ID, "unknown@test.com"));
        }

        @Test
        @DisplayName("completes password reset via admin API")
        void completeReset() {
            UserResource userResource = mock(UserResource.class);
            when(usersResource.get("user-123")).thenReturn(userResource);

            provider.completePasswordReset(TENANT_ID, "user-123", "new-password");

            ArgumentCaptor<CredentialRepresentation> captor =
                    ArgumentCaptor.forClass(CredentialRepresentation.class);
            verify(userResource).resetPassword(captor.capture());

            assertEquals(CredentialRepresentation.PASSWORD, captor.getValue().getType());
            assertEquals("new-password", captor.getValue().getValue());
            assertFalse(captor.getValue().isTemporary());
        }

        @Test
        @DisplayName("throws OperioAuthException when reset token is invalid")
        void throwsOnInvalidResetToken() {
            when(usersResource.get("bad-token"))
                    .thenThrow(new jakarta.ws.rs.NotFoundException("User not found"));

            assertThrows(
                    OperioAuthException.class,
                    () -> provider.completePasswordReset(TENANT_ID, "bad-token", "new-password"));
        }
    }

    // -----------------------------------------------------------------------
    // Token verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Token verification")
    class TokenVerification {

        @Test
        @DisplayName("delegates to JwksTokenVerifier with correct JWKS URL")
        void delegatesToJwksVerifier() {
            IdentityContext expected =
                    new IdentityContext(
                            "user-123",
                            "access-token",
                            "user@test.com",
                            Set.of(),
                            Map.of(),
                            TENANT_ID,
                            "keycloak");
            when(jwksVerifier.verify(JWKS_URL, "access-token", TENANT_ID)).thenReturn(expected);

            IdentityContext result = provider.verifyToken(TENANT_ID, "access-token");

            assertSame(expected, result);
            verify(jwksVerifier).verify(JWKS_URL, "access-token", TENANT_ID);
        }
    }

    // -----------------------------------------------------------------------
    // Tenant lifecycle
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Tenant lifecycle")
    class TenantLifecycle {

        @Test
        @DisplayName("provisionTenant delegates to provisioner and caches secret")
        void provisionTenant() throws Exception {
            when(realmProvisioner.provisionRealm("new-tenant", "New Tenant"))
                    .thenReturn("new-secret");

            provider.provisionTenant("new-tenant", "New Tenant");

            verify(realmProvisioner).provisionRealm("new-tenant", "New Tenant");
            assertEquals("new-secret", getSecretCache(provider).get("new-tenant"));
        }

        @Test
        @DisplayName("deprovisionTenant delegates to provisioner and clears caches")
        void deprovisionTenant() throws Exception {
            getSecretCache(provider).put("old-tenant", "old-secret");

            provider.deprovisionTenant("old-tenant");

            verify(realmProvisioner).deprovisionRealm("old-tenant");
            assertNull(getSecretCache(provider).get("old-tenant"));
            verify(jwksVerifier)
                    .evict(SERVER_URL + "/realms/old-tenant/protocol/openid-connect/certs");
        }
    }

    // -----------------------------------------------------------------------
    // Client secret resolution
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Client secret resolution")
    class ClientSecretResolution {

        @Test
        @DisplayName("uses cached secret when available")
        @SuppressWarnings("unchecked")
        void usesCachedSecret() throws Exception {
            // Secret is pre-populated in setUp(). authenticate() should not
            // query the admin API for the secret.
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(401);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            assertThrows(
                    OperioAuthException.class,
                    () -> provider.authenticate(TENANT_ID, "u@t.com", "p"));

            // Should not have queried admin API for clients
            verify(realmResource, never()).clients();
        }

        @Test
        @DisplayName("uses config secret when no cached secret")
        @SuppressWarnings("unchecked")
        void usesConfigSecret() throws Exception {
            // Create provider with explicit client secret in config
            KeycloakConfig configWithSecret =
                    new KeycloakConfig(
                            SERVER_URL,
                            "master",
                            "admin-cli",
                            "admin",
                            "admin",
                            "operio-backend",
                            "config-secret");
            setField(provider, "config", configWithSecret);

            // Remove cached secret for the tenant
            getSecretCache(provider).remove(TENANT_ID);

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(401);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            assertThrows(
                    OperioAuthException.class,
                    () -> provider.authenticate(TENANT_ID, "u@t.com", "p"));

            // Config secret should now be cached
            assertEquals("config-secret", getSecretCache(provider).get(TENANT_ID));
        }

        @Test
        @DisplayName("throws OperioAuthException when no secret is available")
        void throwsWhenNoSecretAvailable() throws Exception {
            // Remove cached secret and ensure config has no secret
            getSecretCache(provider).remove(TENANT_ID);

            // Mock admin API to return no clients
            ClientsResource clientsResource = mock(ClientsResource.class);
            when(realmResource.clients()).thenReturn(clientsResource);
            when(clientsResource.findByClientId("operio-backend")).thenReturn(List.of());

            OperioAuthException ex =
                    assertThrows(
                            OperioAuthException.class,
                            () -> provider.authenticate(TENANT_ID, "u@t.com", "p"));
            assertTrue(ex.getMessage().contains("No client secret available"));
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, String> getSecretCache(KeycloakIdpProvider provider)
            throws Exception {
        var field = KeycloakIdpProvider.class.getDeclaredField("tenantClientSecrets");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, String>) field.get(provider);
    }
}
