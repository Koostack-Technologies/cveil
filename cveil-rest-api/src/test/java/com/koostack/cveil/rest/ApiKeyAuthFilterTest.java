package com.koostack.cveil.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.platform.iam.AccessTokenGenerator;
import com.koostack.cveil.core.platform.iam.AccessTokenInfo;
import com.koostack.cveil.core.platform.iam.IamQueryService;
import com.koostack.cveil.core.platform.iam.TokenValidationService;
import com.koostack.cveil.rest.config.CveilConfig;
import com.koostack.cveil.rest.config.PlatformCqrsHolder;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.filter.ApiKeyAuthFilter;
import com.koostack.cveil.rest.lifecycle.BootstrapKeyLifecycleService;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ApiKeyAuthFilter} — the JAX-RS authentication filter that verifies Bearer
 * tokens (API keys and bootstrap key) on every request.
 *
 * <p>These tests exercise the filter in isolation using Mockito mocks for all CDI-injected
 * dependencies ({@code Cveil}, {@code CveilConfig}, {@code BootstrapKeyLifecycleService}, {@code
 * CveilRequestHolder}) and a mocked {@link ContainerRequestContext}.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Public paths (health) — filter skips authentication
 *   <li>CORS preflight (OPTIONS) — filter skips authentication
 *   <li>Missing Authorization header → 401
 *   <li>Non-Bearer Authorization header → 401
 *   <li>Empty Bearer token → 401
 *   <li>Valid bootstrap key → super-admin identity with no tenant
 *   <li>Disabled (consumed) bootstrap key → 403 Forbidden
 *   <li>Valid API key (token verifier succeeds) → identity applied
 *   <li>Invalid API key (token verifier rejects) → 401
 *   <li>Token verifier throws unexpected exception → 401
 *   <li>No token verifier configured → 401
 *   <li>Security context role enforcement after auth
 *   <li>X-Forwarded-For / X-Real-IP remote address resolution
 *   <li>User-Agent header pass-through
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyAuthFilter")
class ApiKeyAuthFilterTest {

    // -----------------------------------------------------------------------
    // Mocks
    // -----------------------------------------------------------------------

    @Mock private PlatformCqrsHolder cqrsHolder;

    @Mock private BootstrapKeyLifecycleService bootstrapLifecycle;

    @Mock private CveilRequestHolder requestHolder;

    @Mock private ContainerRequestContext requestContext;

    @SuppressWarnings("unchecked")
    @Mock
    private Instance<CveilConfig> configInstance;

    @Mock private CveilConfig cveilConfig;

    @Mock private UriInfo uriInfo;

    @Mock private SecurityContext securityContext;

    // -----------------------------------------------------------------------
    // System under test
    // -----------------------------------------------------------------------

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new ApiKeyAuthFilter();

        // Inject mocks into the filter via reflection (simulating CDI @Inject)
        injectField(filter, "cqrsHolder", cqrsHolder);
        injectField(filter, "bootstrapLifecycle", bootstrapLifecycle);
        injectField(filter, "requestHolder", requestHolder);
        injectField(filter, "configInstance", configInstance);

        // Config-based API key lookup returns empty map by default
        lenient().when(configInstance.get()).thenReturn(cveilConfig);
        lenient().when(cveilConfig.apiKeys()).thenReturn(Map.of());
    }

    /**
     * Sets a field value on the target object via reflection, used to simulate CDI injection for
     * unit testing.
     */
    private static void injectField(Object target, String fieldName, Object value)
            throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Walks the class hierarchy to find a declared field by name. */
    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(
                "Field '"
                        + fieldName
                        + "' not found in "
                        + clazz.getName()
                        + " or its superclasses");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Configures the mock request context to return the given path. */
    private void givenPath(String path) {
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(uriInfo.getPath()).thenReturn(path);
        try {
            lenient()
                    .when(uriInfo.getRequestUri())
                    .thenReturn(new URI("http://localhost:8080/" + path));
        } catch (Exception ignored) {
        }
    }

    /** Configures the mock request context to return the given HTTP method. */
    private void givenMethod(String method) {
        lenient().when(requestContext.getMethod()).thenReturn(method);
    }

    /** Configures the Authorization header on the request context. */
    private void givenAuthHeader(String value) {
        lenient().when(requestContext.getHeaderString("Authorization")).thenReturn(value);
    }

    /** Sets up a standard non-public GET request with no auth header (default). */
    private void givenAuthenticatedEndpoint(String path) {
        givenPath(path);
        givenMethod("GET");
        lenient().when(requestContext.getSecurityContext()).thenReturn(securityContext);
        lenient().when(securityContext.isSecure()).thenReturn(false);
    }

    /** Creates an IdentityContext for a tenant user. */
    private static IdentityContext tenantIdentity(
            String subject, String tenantId, Set<String> roles) {
        return new IdentityContext(
                subject,
                "test-token-" + subject,
                subject + "@example.com",
                roles,
                Map.of(),
                tenantId,
                "api-key");
    }

    /** Creates an IdentityContext simulating a Personal Access Token (PAT). */
    private static IdentityContext patIdentity(String subject, String tenantId, Set<String> roles) {
        return new IdentityContext(
                subject,
                "cveil_test-token",
                subject + "@example.com",
                roles,
                Map.of(),
                tenantId,
                "access-token");
    }

    /** Captures the Response passed to abortWith(). */
    private Response captureAbort() {
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        return captor.getValue();
    }

    // =======================================================================
    // Public paths — authentication skipped
    // =======================================================================

    @Nested
    @DisplayName("Public paths (authentication skipped)")
    class PublicPaths {

        @Test
        @DisplayName("health endpoint (api/v1/health) skips authentication")
        void healthEndpointSkipped() throws IOException {
            givenPath("api/v1/health");
            givenMethod("GET");

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(requestHolder, never()).set(any(), any(), any());
        }

        @Test
        @DisplayName("health endpoint with leading slash skips authentication")
        void healthEndpointLeadingSlash() throws IOException {
            givenPath("/api/v1/health");
            givenMethod("GET");

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("Quarkus SmallRye health (q/health) skips authentication")
        void quarkusHealthSkipped() throws IOException {
            givenPath("q/health");
            givenMethod("GET");

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("Quarkus health sub-path (q/health/ready) skips authentication")
        void quarkusHealthReadySkipped() throws IOException {
            givenPath("q/health/ready");
            givenMethod("GET");

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("Quarkus health liveness (q/health/live) skips authentication")
        void quarkusHealthLiveSkipped() throws IOException {
            givenPath("q/health/live");
            givenMethod("GET");

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    // =======================================================================
    // CORS preflight — OPTIONS skipped
    // =======================================================================

    @Nested
    @DisplayName("CORS preflight (OPTIONS)")
    class CorsPreflightTests {

        @Test
        @DisplayName("OPTIONS request on consent endpoint skips authentication")
        void optionsSkipped() throws IOException {
            givenPath("api/v1/consent/grant");
            givenMethod("OPTIONS");

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(requestHolder, never()).set(any(), any(), any());
        }

        @Test
        @DisplayName("OPTIONS request on platform endpoint skips authentication")
        void optionsPlatformSkipped() throws IOException {
            givenPath("api/v1/platform/tenants");
            givenMethod("OPTIONS");

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("OPTIONS request (case-insensitive) skips authentication")
        void optionsCaseInsensitive() throws IOException {
            givenPath("api/v1/admin/fields");
            givenMethod("options");

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    // =======================================================================
    // Missing Authorization header → 401
    // =======================================================================

    @Nested
    @DisplayName("Missing Authorization header → 401")
    class MissingAuthHeader {

        @Test
        @DisplayName("no Authorization header → 401 Unauthorized")
        void noAuthHeader() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader(null);

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }

        @Test
        @DisplayName("empty Authorization header → 401 Unauthorized")
        void emptyAuthHeader() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader("");

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }

        @Test
        @DisplayName("abort response has JSON content type")
        void abortHasJsonContentType() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader(null);

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertNotNull(abortResponse.getMediaType());
            assertTrue(
                    abortResponse.getMediaType().toString().contains("application/json"),
                    "Expected JSON content type but got: " + abortResponse.getMediaType());
        }

        @Test
        @DisplayName("abort response body contains error message")
        void abortHasErrorMessage() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader(null);

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            Object entity = abortResponse.getEntity();
            assertNotNull(entity);
            // The entity is a Map with "error" and "message" keys
            assertInstanceOf(Map.class, entity);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entity;
            assertEquals("Unauthorized", body.get("error"));
            assertNotNull(body.get("message"));
        }
    }

    // =======================================================================
    // Non-Bearer Authorization → 401
    // =======================================================================

    @Nested
    @DisplayName("Non-Bearer Authorization header → 401")
    class NonBearerAuth {

        @Test
        @DisplayName("Basic auth header → 401")
        void basicAuthRejected() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Basic dXNlcjpwYXNz");

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }

        @Test
        @DisplayName("ApiKey scheme header → 401")
        void apiKeySchemeRejected() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("ApiKey some-key-value");

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }

        @Test
        @DisplayName("random string (no scheme) → 401")
        void randomStringRejected() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("just-a-random-string");

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }
    }

    // =======================================================================
    // Empty Bearer token → 401
    // =======================================================================

    @Nested
    @DisplayName("Empty Bearer token → 401")
    class EmptyBearerToken {

        @Test
        @DisplayName("Bearer with empty token → 401")
        void emptyToken() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer ");

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }

        @Test
        @DisplayName("Bearer with whitespace-only token → 401")
        void whitespaceOnlyToken() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer    ");

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }
    }

    // =======================================================================
    // Valid bootstrap key → super-admin
    // =======================================================================

    @Nested
    @DisplayName("Valid bootstrap key → super-admin access")
    class ValidBootstrapKey {

        @Test
        @DisplayName("active bootstrap key grants super-admin identity")
        void bootstrapKeyGrantsSuperAdmin() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/tenants");
            givenAuthHeader("Bearer my-bootstrap-secret");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-secret")).thenReturn(true);

            filter.filter(requestContext);

            // Should NOT abort
            verify(requestContext, never()).abortWith(any());

            // Should populate the request holder with a super-admin identity
            ArgumentCaptor<IdentityContext> identityCaptor =
                    ArgumentCaptor.forClass(IdentityContext.class);
            verify(requestHolder).set(identityCaptor.capture(), any(), any());

            IdentityContext identity = identityCaptor.getValue();
            assertEquals("bootstrap-admin", identity.subject());
            assertNull(identity.tenantId(), "Bootstrap identity should have no tenant");
            assertTrue(
                    identity.roles().contains("super-admin"),
                    "Bootstrap identity should have super-admin role");
            assertEquals("bootstrap-key", identity.provider());
        }

        @Test
        @DisplayName("bootstrap key sets SecurityContext on the request")
        void bootstrapKeySetsSecurityContext() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/tenants");
            givenAuthHeader("Bearer my-bootstrap-secret");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-secret")).thenReturn(true);

            filter.filter(requestContext);

            // SecurityContext should be set (setSecurityContext called)
            verify(requestContext).setSecurityContext(any(SecurityContext.class));
        }

        @Test
        @DisplayName("bootstrap key stores IdentityContext as request property")
        void bootstrapKeyStoresRequestProperty() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/tenants");
            givenAuthHeader("Bearer my-bootstrap-secret");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-secret")).thenReturn(true);

            filter.filter(requestContext);

            verify(requestContext)
                    .setProperty(
                            eq(ApiKeyAuthFilter.IDENTITY_CONTEXT_PROPERTY),
                            any(IdentityContext.class));
        }
    }

    // =======================================================================
    // Disabled (consumed) bootstrap key → 403
    // =======================================================================

    @Nested
    @DisplayName("Disabled bootstrap key → 403 Forbidden")
    class DisabledBootstrapKey {

        @Test
        @DisplayName("consumed bootstrap key returns 403")
        void consumedBootstrapKey403() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/tenants");
            givenAuthHeader("Bearer my-bootstrap-secret");
            // isActiveBootstrapKey returns false (key is consumed/disabled)
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-secret")).thenReturn(false);
            // isBootstrapKeyValue returns true (it IS the bootstrap key, just disabled)
            when(bootstrapLifecycle.isBootstrapKeyValue("my-bootstrap-secret")).thenReturn(true);

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(403, abortResponse.getStatus());
        }

        @Test
        @DisplayName("consumed bootstrap key response includes descriptive message")
        void consumedBootstrapKeyMessage() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/tenants");
            givenAuthHeader("Bearer my-bootstrap-secret");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-secret")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("my-bootstrap-secret")).thenReturn(true);

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            Object entity = abortResponse.getEntity();
            assertNotNull(entity);
            assertInstanceOf(Map.class, entity);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entity;
            String message = (String) body.get("message");
            assertNotNull(message);
            assertTrue(
                    message.contains("disabled") || message.contains("Bootstrap"),
                    "Expected message about disabled bootstrap key but got: " + message);
        }

        @Test
        @DisplayName("consumed bootstrap key does NOT populate request holder")
        void consumedBootstrapKeyNoHolder() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/tenants");
            givenAuthHeader("Bearer my-bootstrap-secret");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-secret")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("my-bootstrap-secret")).thenReturn(true);

            filter.filter(requestContext);

            verify(requestHolder, never()).set(any(), any(), any());
        }
    }

    // =======================================================================
    // Valid API key (token verifier succeeds) → identity applied
    // =======================================================================

    @Nested
    @DisplayName("Valid API key → identity applied")
    class ValidApiKey {

        @Test
        @DisplayName("valid API key authenticates successfully")
        void validApiKeyAuthenticated() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_abc123def456");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_abc123def456"))
                    .thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_abc123def456")).thenReturn(false);

            IdentityContext identity = tenantIdentity("alice", "acme_corp", Set.of("admin"));
            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_abc123def456")).thenReturn(identity);

            filter.filter(requestContext);

            // Should NOT abort
            verify(requestContext, never()).abortWith(any());

            // Should populate the request holder with the identity
            verify(requestHolder).set(eq(identity), any(), any());
        }

        @Test
        @DisplayName("valid API key sets SecurityContext")
        void validApiKeySetsSecurityContext() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_valid_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_valid_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_valid_key")).thenReturn(false);

            IdentityContext identity = tenantIdentity("bob", "beta_co", Set.of("consent-writer"));
            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_valid_key")).thenReturn(identity);

            filter.filter(requestContext);

            // SecurityContext should be set
            ArgumentCaptor<SecurityContext> secCaptor =
                    ArgumentCaptor.forClass(SecurityContext.class);
            verify(requestContext).setSecurityContext(secCaptor.capture());

            SecurityContext sc = secCaptor.getValue();
            assertNotNull(sc.getUserPrincipal());
            assertEquals("bob", sc.getUserPrincipal().getName());
            assertTrue(sc.isUserInRole("consent-writer"));
            assertFalse(sc.isUserInRole("super-admin"));
            assertEquals("BEARER", sc.getAuthenticationScheme());
        }

        @Test
        @DisplayName("valid API key stores IdentityContext as request property")
        void validApiKeyStoresProperty() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_prop_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_prop_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_prop_key")).thenReturn(false);

            IdentityContext identity = tenantIdentity("carol", "gamma_inc", Set.of("reader"));
            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_prop_key")).thenReturn(identity);

            filter.filter(requestContext);

            verify(requestContext)
                    .setProperty(eq(ApiKeyAuthFilter.IDENTITY_CONTEXT_PROPERTY), eq(identity));
        }

        @Test
        @DisplayName("valid API key with multiple roles preserves all roles")
        void validApiKeyMultipleRoles() throws IOException {
            givenAuthenticatedEndpoint("api/v1/admin/fields");
            givenAuthHeader("Bearer opr_live_multi_role");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_multi_role")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_multi_role")).thenReturn(false);

            Set<String> roles = Set.of("admin", "consent-writer", "consent-reader", "audit-viewer");
            IdentityContext identity = tenantIdentity("dave", "delta_llc", roles);
            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_multi_role")).thenReturn(identity);

            filter.filter(requestContext);

            ArgumentCaptor<SecurityContext> secCaptor =
                    ArgumentCaptor.forClass(SecurityContext.class);
            verify(requestContext).setSecurityContext(secCaptor.capture());
            SecurityContext sc = secCaptor.getValue();

            assertTrue(sc.isUserInRole("admin"));
            assertTrue(sc.isUserInRole("consent-writer"));
            assertTrue(sc.isUserInRole("consent-reader"));
            assertTrue(sc.isUserInRole("audit-viewer"));
            assertFalse(sc.isUserInRole("nonexistent-role"));
        }
    }

    // =======================================================================
    // Invalid API key (token verifier rejects) → 401
    // =======================================================================

    @Nested
    @DisplayName("Invalid API key → 401")
    class InvalidApiKey {

        @Test
        @DisplayName("token verifier throws CveilAuthException → 401")
        void tokenValidationServiceRejectsToken() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_invalid_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_invalid_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_invalid_key")).thenReturn(false);

            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_invalid_key"))
                    .thenThrow(new CveilAuthException("Invalid or expired API key"));

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }

        @Test
        @DisplayName("token verifier throws CveilAuthException → holder NOT populated")
        void tokenValidationServiceRejectsNoHolder() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_bad_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_bad_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_bad_key")).thenReturn(false);

            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_bad_key"))
                    .thenThrow(new CveilAuthException("Key revoked"));

            filter.filter(requestContext);

            verify(requestHolder, never()).set(any(), any(), any());
        }

        @Test
        @DisplayName("revoked key returns 401 with descriptive message")
        void revokedKeyMessage() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_revoked");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_revoked")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_revoked")).thenReturn(false);

            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_revoked"))
                    .thenThrow(new CveilAuthException("API key has been revoked"));

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
            Object entity = abortResponse.getEntity();
            assertInstanceOf(Map.class, entity);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entity;
            String message = (String) body.get("message");
            assertTrue(
                    message.contains("revoked"),
                    "Expected message about revoked key but got: " + message);
        }
    }

    // =======================================================================
    // Token verifier throws unexpected exception → 401
    // =======================================================================

    @Nested
    @DisplayName("Unexpected token verifier exception → 401")
    class UnexpectedVerifierException {

        @Test
        @DisplayName("RuntimeException from token verifier → 401")
        void unexpectedExceptionReturns401() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_crash_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_crash_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_crash_key")).thenReturn(false);

            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_crash_key"))
                    .thenThrow(new RuntimeException("Database connection lost"));

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }

        @Test
        @DisplayName("NullPointerException from token verifier → 401 (no leak)")
        void npeReturns401() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_npe_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_npe_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_npe_key")).thenReturn(false);

            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_npe_key"))
                    .thenThrow(new NullPointerException("Null config"));

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());

            // Message should NOT contain the internal NPE details
            Object entity = abortResponse.getEntity();
            assertInstanceOf(Map.class, entity);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entity;
            String message = (String) body.get("message");
            assertFalse(
                    message.contains("Null config"),
                    "Internal error details should not be leaked to the client");
        }
    }

    // =======================================================================
    // No token verifier configured → 401
    // =======================================================================

    @Nested
    @DisplayName("No token verifier configured → 401")
    class NoTokenValidationService {

        @Test
        @DisplayName("null token verifier → 401 with configuration error message")
        void nullTokenValidationService() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_some_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_some_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_some_key")).thenReturn(false);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(false);

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(401, abortResponse.getStatus());
        }
    }

    // =======================================================================
    // Remote address resolution (X-Forwarded-For, X-Real-IP)
    // =======================================================================

    @Nested
    @DisplayName("Remote address resolution")
    class RemoteAddressResolution {

        @Test
        @DisplayName("X-Forwarded-For header is used for remote address (single IP)")
        void xForwardedForSingleIp() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader("Bearer my-bootstrap-key");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-key")).thenReturn(true);
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("203.0.113.42");
            when(requestContext.getHeaderString("User-Agent")).thenReturn("TestAgent/1.0");

            filter.filter(requestContext);

            ArgumentCaptor<String> addrCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestHolder).set(any(), addrCaptor.capture(), any());
            assertEquals("203.0.113.42", addrCaptor.getValue());
        }

        @Test
        @DisplayName("X-Forwarded-For with multiple IPs uses the first (client IP)")
        void xForwardedForMultipleIps() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader("Bearer my-bootstrap-key");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-key")).thenReturn(true);
            when(requestContext.getHeaderString("X-Forwarded-For"))
                    .thenReturn("203.0.113.42, 10.0.0.1, 172.16.0.1");
            when(requestContext.getHeaderString("User-Agent")).thenReturn("TestAgent/1.0");

            filter.filter(requestContext);

            ArgumentCaptor<String> addrCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestHolder).set(any(), addrCaptor.capture(), any());
            assertEquals("203.0.113.42", addrCaptor.getValue());
        }

        @Test
        @DisplayName("X-Real-IP is used when X-Forwarded-For is absent")
        void xRealIpFallback() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader("Bearer my-bootstrap-key");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-key")).thenReturn(true);
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(null);
            when(requestContext.getHeaderString("X-Real-IP")).thenReturn("198.51.100.7");
            when(requestContext.getHeaderString("User-Agent")).thenReturn("TestAgent/1.0");

            filter.filter(requestContext);

            ArgumentCaptor<String> addrCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestHolder).set(any(), addrCaptor.capture(), any());
            assertEquals("198.51.100.7", addrCaptor.getValue());
        }

        @Test
        @DisplayName("null remote address when no proxy headers present")
        void noProxyHeaders() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader("Bearer my-bootstrap-key");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-key")).thenReturn(true);
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(null);
            when(requestContext.getHeaderString("X-Real-IP")).thenReturn(null);
            when(requestContext.getHeaderString("User-Agent")).thenReturn(null);

            filter.filter(requestContext);

            ArgumentCaptor<String> addrCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> uaCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestHolder).set(any(), addrCaptor.capture(), uaCaptor.capture());
            assertNull(addrCaptor.getValue());
            assertNull(uaCaptor.getValue());
        }
    }

    // =======================================================================
    // User-Agent header pass-through
    // =======================================================================

    @Nested
    @DisplayName("User-Agent header pass-through")
    class UserAgentPassThrough {

        @Test
        @DisplayName("User-Agent header is passed to request holder")
        void userAgentPassedToHolder() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader("Bearer my-bootstrap-key");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-key")).thenReturn(true);
            lenient()
                    .when(requestContext.getHeaderString("User-Agent"))
                    .thenReturn("CveilSDK/2.1 Java/21");

            filter.filter(requestContext);

            ArgumentCaptor<String> uaCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestHolder).set(any(), any(), uaCaptor.capture());
            assertEquals("CveilSDK/2.1 Java/21", uaCaptor.getValue());
        }

        @Test
        @DisplayName("null User-Agent is passed as null")
        void nullUserAgent() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader("Bearer my-bootstrap-key");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-key")).thenReturn(true);
            lenient().when(requestContext.getHeaderString("User-Agent")).thenReturn(null);

            filter.filter(requestContext);

            ArgumentCaptor<String> uaCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestHolder).set(any(), any(), uaCaptor.capture());
            assertNull(uaCaptor.getValue());
        }
    }

    // =======================================================================
    // SecurityContext role enforcement (after auth)
    // =======================================================================

    @Nested
    @DisplayName("SecurityContext role enforcement")
    class SecurityContextRoleEnforcement {

        @Test
        @DisplayName("bootstrap identity has super-admin role in SecurityContext")
        void bootstrapHasSuperAdminRole() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/tenants");
            givenAuthHeader("Bearer my-bootstrap-key");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-key")).thenReturn(true);

            filter.filter(requestContext);

            ArgumentCaptor<SecurityContext> secCaptor =
                    ArgumentCaptor.forClass(SecurityContext.class);
            verify(requestContext).setSecurityContext(secCaptor.capture());

            SecurityContext sc = secCaptor.getValue();
            assertTrue(sc.isUserInRole("super-admin"));
            assertFalse(sc.isUserInRole("admin"));
            assertFalse(sc.isUserInRole("consent-writer"));
        }

        @Test
        @DisplayName("tenant API key has correct roles but NOT super-admin")
        void tenantKeyDoesNotHaveSuperAdmin() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader("Bearer opr_live_tenant_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_tenant_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_tenant_key")).thenReturn(false);

            IdentityContext identity =
                    tenantIdentity("alice", "acme_corp", Set.of("consent-writer"));
            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_tenant_key")).thenReturn(identity);

            filter.filter(requestContext);

            ArgumentCaptor<SecurityContext> secCaptor =
                    ArgumentCaptor.forClass(SecurityContext.class);
            verify(requestContext).setSecurityContext(secCaptor.capture());

            SecurityContext sc = secCaptor.getValue();
            assertTrue(sc.isUserInRole("consent-writer"));
            assertFalse(
                    sc.isUserInRole("super-admin"),
                    "Tenant API key should NOT have super-admin role");
        }

        @Test
        @DisplayName("SecurityContext.getUserPrincipal() returns correct subject")
        void principalReturnsSubject() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_principal_key");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_principal_key"))
                    .thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_principal_key"))
                    .thenReturn(false);

            IdentityContext identity = tenantIdentity("eve", "echo_corp", Set.of("reader"));
            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_principal_key")).thenReturn(identity);

            filter.filter(requestContext);

            ArgumentCaptor<SecurityContext> secCaptor =
                    ArgumentCaptor.forClass(SecurityContext.class);
            verify(requestContext).setSecurityContext(secCaptor.capture());

            SecurityContext sc = secCaptor.getValue();
            assertNotNull(sc.getUserPrincipal());
            assertEquals("eve", sc.getUserPrincipal().getName());
        }

        @Test
        @DisplayName("SecurityContext.isUserInRole(null) returns false gracefully")
        void nullRoleReturnsFalse() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer my-bootstrap-key");
            when(bootstrapLifecycle.isActiveBootstrapKey("my-bootstrap-key")).thenReturn(true);

            filter.filter(requestContext);

            ArgumentCaptor<SecurityContext> secCaptor =
                    ArgumentCaptor.forClass(SecurityContext.class);
            verify(requestContext).setSecurityContext(secCaptor.capture());

            SecurityContext sc = secCaptor.getValue();
            assertFalse(sc.isUserInRole(null));
        }
    }

    // =======================================================================
    // Non-public path without auth on various endpoints
    // =======================================================================

    @Nested
    @DisplayName("Various protected endpoints require auth")
    class VariousProtectedEndpoints {

        @Test
        @DisplayName("consent/grant requires auth")
        void consentGrantRequiresAuth() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/grant");
            givenAuthHeader(null);

            filter.filter(requestContext);

            verify(requestContext).abortWith(any());
        }

        @Test
        @DisplayName("consent/evaluate requires auth")
        void consentEvaluateRequiresAuth() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader(null);

            filter.filter(requestContext);

            verify(requestContext).abortWith(any());
        }

        @Test
        @DisplayName("admin/fields requires auth")
        void adminFieldsRequiresAuth() throws IOException {
            givenAuthenticatedEndpoint("api/v1/admin/fields");
            givenAuthHeader(null);

            filter.filter(requestContext);

            verify(requestContext).abortWith(any());
        }

        @Test
        @DisplayName("platform/tenants requires auth")
        void platformTenantsRequiresAuth() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/tenants");
            givenAuthHeader(null);

            filter.filter(requestContext);

            verify(requestContext).abortWith(any());
        }

        @Test
        @DisplayName("platform/api-keys requires auth")
        void platformApiKeysRequiresAuth() throws IOException {
            givenAuthenticatedEndpoint("api/v1/platform/api-keys");
            givenAuthHeader(null);

            filter.filter(requestContext);

            verify(requestContext).abortWith(any());
        }
    }

    // =======================================================================
    // PAT endpoint restriction — PATs rejected on non-billable paths
    // =======================================================================

    @Nested
    @DisplayName("PAT endpoint restriction")
    class PatEndpointRestriction {

        private AccessTokenGenerator.GeneratedToken generatedToken;

        @SuppressWarnings("unchecked")
        private IamQueryService<Object> iamQueryService;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpPatFlow() {
            generatedToken = AccessTokenGenerator.generate();
            iamQueryService = mock(IamQueryService.class);
            lenient().when(cqrsHolder.isCqrsAvailable()).thenReturn(true);
            lenient().when(cqrsHolder.iamQueryService()).thenReturn(iamQueryService);
        }

        private AccessTokenInfo activeTokenInfo(String tenantId, String roles) {
            return new AccessTokenInfo(
                    1L,
                    generatedToken.tokenPrefix(),
                    "test-pat",
                    tenantId,
                    roles,
                    "active",
                    Instant.now().plusSeconds(3600),
                    "test-user",
                    null,
                    Instant.now());
        }

        @Test
        @DisplayName("PAT on billable endpoint (consent) → allowed")
        void patOnBillableEndpointAllowed() throws IOException {
            givenAuthenticatedEndpoint("api/v1/subjects/consent/grant");
            givenMethod("POST");
            givenAuthHeader("Bearer " + generatedToken.plaintextToken());
            when(bootstrapLifecycle.isActiveBootstrapKey(generatedToken.plaintextToken()))
                    .thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue(generatedToken.plaintextToken()))
                    .thenReturn(false);
            when(iamQueryService.findAccessTokenByHash(generatedToken.tokenHash()))
                    .thenReturn(activeTokenInfo("pat_tenant", "consent:write"));

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("PAT on billable endpoint (governance) → allowed")
        void patOnGovernanceAllowed() throws IOException {
            givenAuthenticatedEndpoint("api/v1/governance/purposes");
            givenMethod("GET");
            givenAuthHeader("Bearer " + generatedToken.plaintextToken());
            when(bootstrapLifecycle.isActiveBootstrapKey(generatedToken.plaintextToken()))
                    .thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue(generatedToken.plaintextToken()))
                    .thenReturn(false);
            when(iamQueryService.findAccessTokenByHash(generatedToken.tokenHash()))
                    .thenReturn(activeTokenInfo("pat_tenant", "governance:read"));

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("PAT on non-billable endpoint (billing) → 403 Forbidden")
        void patOnBillingEndpointRejected() throws IOException {
            givenAuthenticatedEndpoint("api/v1/billing/subscription");
            givenMethod("GET");
            givenAuthHeader("Bearer " + generatedToken.plaintextToken());
            when(bootstrapLifecycle.isActiveBootstrapKey(generatedToken.plaintextToken()))
                    .thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue(generatedToken.plaintextToken()))
                    .thenReturn(false);
            when(iamQueryService.findAccessTokenByHash(generatedToken.tokenHash()))
                    .thenReturn(activeTokenInfo("pat_tenant", "consent:write"));

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(403, abortResponse.getStatus());
        }

        @Test
        @DisplayName("PAT on non-billable endpoint (metrics) → 403 Forbidden")
        void patOnMetricsEndpointRejected() throws IOException {
            givenAuthenticatedEndpoint("api/v1/metrics");
            givenMethod("GET");
            givenAuthHeader("Bearer " + generatedToken.plaintextToken());
            when(bootstrapLifecycle.isActiveBootstrapKey(generatedToken.plaintextToken()))
                    .thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue(generatedToken.plaintextToken()))
                    .thenReturn(false);
            when(iamQueryService.findAccessTokenByHash(generatedToken.tokenHash()))
                    .thenReturn(activeTokenInfo("pat_tenant", "consent:read"));

            filter.filter(requestContext);

            Response abortResponse = captureAbort();
            assertEquals(403, abortResponse.getStatus());
        }

        @Test
        @DisplayName("Config API key on non-billable endpoint → allowed (no PAT restriction)")
        void configKeyOnBillingEndpointAllowed() throws IOException {
            // Config-based API keys have provider "config-api-key", not "access-token"
            String configToken = "dev-test-key-123";
            when(cveilConfig.apiKeys())
                    .thenReturn(Map.of(configToken, "role:admin,tenant:test_tenant"));
            givenAuthenticatedEndpoint("api/v1/billing/subscription");
            givenMethod("GET");
            givenAuthHeader("Bearer " + configToken);
            when(bootstrapLifecycle.isActiveBootstrapKey(configToken)).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue(configToken)).thenReturn(false);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    // =======================================================================
    // Token with leading/trailing whitespace is trimmed
    // =======================================================================

    @Nested
    @DisplayName("Token whitespace handling")
    class TokenWhitespaceHandling {

        @Test
        @DisplayName("token with trailing whitespace is trimmed before verification")
        void trailingWhitespace() throws IOException {
            givenAuthenticatedEndpoint("api/v1/consent/evaluate");
            givenAuthHeader("Bearer opr_live_padded_key  ");
            when(bootstrapLifecycle.isActiveBootstrapKey("opr_live_padded_key")).thenReturn(false);
            when(bootstrapLifecycle.isBootstrapKeyValue("opr_live_padded_key")).thenReturn(false);

            IdentityContext identity = tenantIdentity("frank", "foxtrot_inc", Set.of("admin"));
            @SuppressWarnings("unchecked")
            TokenValidationService<Object> tokenValidationService =
                    mock(TokenValidationService.class);
            when(cqrsHolder.isTokenValidationAvailable()).thenReturn(true);
            when(cqrsHolder.tokenValidationService()).thenReturn(tokenValidationService);
            when(tokenValidationService.verifyToken("opr_live_padded_key")).thenReturn(identity);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(requestHolder).set(eq(identity), any(), any());
        }
    }
}
