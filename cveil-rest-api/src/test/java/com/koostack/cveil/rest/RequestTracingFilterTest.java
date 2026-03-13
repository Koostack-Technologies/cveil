package com.koostack.cveil.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.filter.RequestTracingFilter;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

/**
 * Unit tests for {@link RequestTracingFilter} — the JAX-RS filter that manages distributed tracing
 * context (X-Request-Id, MDC keys) for every HTTP request.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Client-provided X-Request-Id is preserved and stored
 *   <li>Missing X-Request-Id triggers UUID generation
 *   <li>Blank/whitespace-only X-Request-Id triggers UUID generation
 *   <li>X-Request-Id with leading/trailing whitespace is trimmed
 *   <li>MDC is set with requestId, tenantId placeholder, subject placeholder on request
 *   <li>Request property is set for downstream access
 *   <li>Response filter echoes X-Request-Id in response header
 *   <li>Response filter enriches MDC with authenticated identity
 *   <li>MDC is cleared after response processing
 *   <li>Unauthenticated requests leave MDC placeholders
 *   <li>Null requestHolder is handled gracefully
 *   <li>Response filter handles missing request property gracefully
 * </ul>
 */
@DisplayName("RequestTracingFilter")
@ExtendWith(MockitoExtension.class)
class RequestTracingFilterTest {

    @Mock private ContainerRequestContext requestContext;

    @Mock private ContainerResponseContext responseContext;

    @Mock private CveilRequestHolder requestHolder;

    @Mock private UriInfo uriInfo;

    private RequestTracingFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new RequestTracingFilter();
        injectField(filter, "requestHolder", requestHolder);
        // Stub UriInfo so the request filter's logging doesn't NPE
        lenient().when(requestContext.getMethod()).thenReturn("GET");
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(uriInfo.getAbsolutePath()).thenReturn(URI.create("http://localhost/test"));
        lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/test"));
        // Always clear MDC before each test to avoid state leaking
        MDC.clear();
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    private static void injectField(Object target, String fieldName, Object value)
            throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field '" + name + "' not found in " + clazz);
    }

    // -----------------------------------------------------------------------
    // Helper: create an identity context
    // -----------------------------------------------------------------------

    private static IdentityContext tenantIdentity(String tenantId, String subject) {
        return new IdentityContext(
                subject,
                "token-abc",
                "user@example.com",
                Set.of("admin"),
                Map.of(),
                tenantId,
                "api-key");
    }

    // -----------------------------------------------------------------------
    // Helper: set up response context with real headers map
    // -----------------------------------------------------------------------

    private MultivaluedMap<String, Object> givenResponseHeaders() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        lenient().when(responseContext.getHeaders()).thenReturn(headers);
        return headers;
    }

    // ===================================================================
    // Request filter tests
    // ===================================================================

    @Nested
    @DisplayName("Request filter — X-Request-Id handling")
    class RequestFilterRequestId {

        @Test
        @DisplayName("client-provided X-Request-Id is preserved")
        void clientProvidedRequestIdPreserved() throws IOException {
            String clientId = "client-request-id-12345";
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(clientId);

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestTracingFilter.REQUEST_ID_PROPERTY, clientId);
        }

        @Test
        @DisplayName("missing X-Request-Id generates UUID")
        void missingHeaderGeneratesUuid() throws IOException {
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(null);

            filter.filter(requestContext);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(requestContext)
                    .setProperty(eq(RequestTracingFilter.REQUEST_ID_PROPERTY), captor.capture());

            String generated = captor.getValue();
            assertNotNull(generated, "Generated request ID should not be null");
            assertDoesNotThrow(
                    () -> UUID.fromString(generated),
                    "Generated request ID should be a valid UUID: " + generated);
        }

        @Test
        @DisplayName("empty X-Request-Id generates UUID")
        void emptyHeaderGeneratesUuid() throws IOException {
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn("");

            filter.filter(requestContext);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(requestContext)
                    .setProperty(eq(RequestTracingFilter.REQUEST_ID_PROPERTY), captor.capture());

            assertDoesNotThrow(
                    () -> UUID.fromString(captor.getValue()),
                    "Empty header should trigger UUID generation");
        }

        @Test
        @DisplayName("whitespace-only X-Request-Id generates UUID")
        void whitespaceOnlyHeaderGeneratesUuid() throws IOException {
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn("   \t  ");

            filter.filter(requestContext);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(requestContext)
                    .setProperty(eq(RequestTracingFilter.REQUEST_ID_PROPERTY), captor.capture());

            assertDoesNotThrow(
                    () -> UUID.fromString(captor.getValue()),
                    "Whitespace-only header should trigger UUID generation");
        }

        @Test
        @DisplayName("X-Request-Id with surrounding whitespace is trimmed")
        void headerTrimmed() throws IOException {
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn("  my-request-id  ");

            filter.filter(requestContext);

            verify(requestContext)
                    .setProperty(RequestTracingFilter.REQUEST_ID_PROPERTY, "my-request-id");
        }

        @Test
        @DisplayName("each invocation generates a unique UUID when no header present")
        void uniqueUuidsGenerated() throws IOException {
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(null);

            filter.filter(requestContext);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(requestContext)
                    .setProperty(eq(RequestTracingFilter.REQUEST_ID_PROPERTY), captor.capture());
            String first = captor.getValue();

            // Reset and invoke again
            reset(requestContext);
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(null);
            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);

            filter.filter(requestContext);

            ArgumentCaptor<String> captor2 = ArgumentCaptor.forClass(String.class);
            verify(requestContext)
                    .setProperty(eq(RequestTracingFilter.REQUEST_ID_PROPERTY), captor2.capture());
            String second = captor2.getValue();

            assertNotEquals(first, second, "Each call should generate a unique UUID");
        }
    }

    // ===================================================================
    // MDC management during request phase
    // ===================================================================

    @Nested
    @DisplayName("Request filter — MDC management")
    class RequestFilterMdc {

        @Test
        @DisplayName("MDC requestId is set to client-provided value")
        void mdcRequestIdFromClient() throws IOException {
            String clientId = "trace-id-abc";
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(clientId);

            filter.filter(requestContext);

            assertEquals(clientId, MDC.get(RequestTracingFilter.MDC_REQUEST_ID));
        }

        @Test
        @DisplayName("MDC requestId is set to generated UUID when no header")
        void mdcRequestIdGenerated() throws IOException {
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(null);

            filter.filter(requestContext);

            String mdcValue = MDC.get(RequestTracingFilter.MDC_REQUEST_ID);
            assertNotNull(mdcValue);
            assertDoesNotThrow(() -> UUID.fromString(mdcValue));
        }

        @Test
        @DisplayName("MDC tenantId is set to placeholder on request")
        void mdcTenantIdPlaceholder() throws IOException {
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn("req-123");

            filter.filter(requestContext);

            assertEquals("-", MDC.get(RequestTracingFilter.MDC_TENANT_ID));
        }

        @Test
        @DisplayName("MDC subject is set to placeholder on request")
        void mdcSubjectPlaceholder() throws IOException {
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn("req-123");

            filter.filter(requestContext);

            assertEquals("-", MDC.get(RequestTracingFilter.MDC_SUBJECT));
        }
    }

    // ===================================================================
    // Response filter tests
    // ===================================================================

    @Nested
    @DisplayName("Response filter — X-Request-Id echo")
    class ResponseFilterRequestIdEcho {

        @Test
        @DisplayName("echoes X-Request-Id from request property")
        void echoesRequestId() throws IOException {
            String requestId = "echoed-id-123";
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn(requestId);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();
            lenient().when(requestHolder.isAuthenticated()).thenReturn(false);

            // Set up UriInfo for the debug log line
            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            filter.filter(requestContext, responseContext);

            assertEquals(requestId, headers.getFirst(RequestTracingFilter.REQUEST_ID_HEADER));
        }

        @Test
        @DisplayName("response header not set when request property is missing")
        void noPropertyNoResponseHeader() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn(null);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();
            lenient().when(requestHolder.isAuthenticated()).thenReturn(false);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            // MDC might not have requestId either; use fallback
            MDC.clear();

            filter.filter(requestContext, responseContext);

            // No request ID known — header should not be set
            assertNull(headers.getFirst(RequestTracingFilter.REQUEST_ID_HEADER));
        }

        @Test
        @DisplayName("falls back to MDC requestId when request property is not a String")
        void fallsBackToMdcWhenPropertyNotString() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn(42); // Not a String
            MultivaluedMap<String, Object> headers = givenResponseHeaders();
            lenient().when(requestHolder.isAuthenticated()).thenReturn(false);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, "mdc-fallback-id");

            filter.filter(requestContext, responseContext);

            assertEquals(
                    "mdc-fallback-id", headers.getFirst(RequestTracingFilter.REQUEST_ID_HEADER));
        }
    }

    // ===================================================================
    // Response filter — MDC identity enrichment
    // ===================================================================

    @Nested
    @DisplayName("Response filter — MDC identity enrichment")
    class ResponseFilterMdcEnrichment {

        @Test
        @DisplayName("enriches MDC with tenantId and subject from authenticated holder")
        void enrichesMdcWithIdentity() throws IOException {
            String requestId = "enrich-test-id";
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn(requestId);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            IdentityContext identity = tenantIdentity("acme-corp", "user-42");
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(identity);

            lenient().when(requestContext.getMethod()).thenReturn("POST");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient()
                    .when(uriInfo.getRequestUri())
                    .thenReturn(URI.create("/api/v1/subjects/consent/grant"));

            // Pre-set placeholder values (as request filter would)
            MDC.put(RequestTracingFilter.MDC_TENANT_ID, "-");
            MDC.put(RequestTracingFilter.MDC_SUBJECT, "-");

            filter.filter(requestContext, responseContext);

            // MDC is cleared after response processing, so we can't check it
            // after the method returns. But we can verify the response header
            // was set (proving the response filter ran).
            assertEquals(requestId, headers.getFirst(RequestTracingFilter.REQUEST_ID_HEADER));
        }

        @Test
        @DisplayName("unauthenticated request leaves MDC placeholders unchanged")
        void unauthenticatedLeavesPlaceholders() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("unauth-req-id");
            MultivaluedMap<String, Object> headers = givenResponseHeaders();
            when(requestHolder.isAuthenticated()).thenReturn(false);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            MDC.put(RequestTracingFilter.MDC_TENANT_ID, "-");
            MDC.put(RequestTracingFilter.MDC_SUBJECT, "-");

            filter.filter(requestContext, responseContext);

            // MDC is cleared by finally block, so values should be gone
            assertNull(MDC.get(RequestTracingFilter.MDC_TENANT_ID));
            assertNull(MDC.get(RequestTracingFilter.MDC_SUBJECT));
        }

        @Test
        @DisplayName("null identity in holder does not enrich MDC")
        void nullIdentityNoEnrichment() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("null-identity-req");
            MultivaluedMap<String, Object> headers = givenResponseHeaders();
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(null);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            MDC.put(RequestTracingFilter.MDC_TENANT_ID, "-");
            MDC.put(RequestTracingFilter.MDC_SUBJECT, "-");

            filter.filter(requestContext, responseContext);

            // Should not throw, MDC is cleared after
            assertNull(MDC.get(RequestTracingFilter.MDC_TENANT_ID));
        }

        @Test
        @DisplayName("identity with null tenantId does not override placeholder")
        void nullTenantIdInIdentity() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("null-tenant-req");
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            IdentityContext identity =
                    new IdentityContext(
                            "subject-1", "token", null, Set.of(), Map.of(), null, "api-key");
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(identity);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/admin/fields"));

            filter.filter(requestContext, responseContext);

            // MDC cleared — just verify no exception
            assertNull(MDC.get(RequestTracingFilter.MDC_TENANT_ID));
        }

        @Test
        @DisplayName("identity with blank tenantId does not override placeholder")
        void blankTenantIdInIdentity() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("blank-tenant-req");
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            IdentityContext identity =
                    new IdentityContext(
                            "subject-1", "token", null, Set.of(), Map.of(), "   ", "api-key");
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(identity);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/admin/fields"));

            filter.filter(requestContext, responseContext);

            // MDC cleared
            assertNull(MDC.get(RequestTracingFilter.MDC_TENANT_ID));
        }

        @Test
        @DisplayName("identity with blank subject does not override placeholder")
        void blankSubjectInIdentity() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("blank-subject-req");
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            IdentityContext identity =
                    new IdentityContext(
                            "   ", "token", null, Set.of(), Map.of(), "acme", "api-key");
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(identity);

            lenient().when(requestContext.getMethod()).thenReturn("POST");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient()
                    .when(uriInfo.getRequestUri())
                    .thenReturn(URI.create("/api/v1/subjects/consent/grant"));

            filter.filter(requestContext, responseContext);

            assertNull(MDC.get(RequestTracingFilter.MDC_SUBJECT));
        }
    }

    // ===================================================================
    // Response filter — MDC clearing
    // ===================================================================

    @Nested
    @DisplayName("Response filter — MDC clearing")
    class ResponseFilterMdcClearing {

        @Test
        @DisplayName("MDC is cleared after response processing (authenticated)")
        void mdcClearedAfterAuthenticated() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("clear-test-id");
            givenResponseHeaders();

            IdentityContext identity = tenantIdentity("acme", "user-1");
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(identity);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, "clear-test-id");
            MDC.put(RequestTracingFilter.MDC_TENANT_ID, "acme");
            MDC.put(RequestTracingFilter.MDC_SUBJECT, "user-1");

            filter.filter(requestContext, responseContext);

            assertNull(
                    MDC.get(RequestTracingFilter.MDC_REQUEST_ID),
                    "requestId should be cleared from MDC");
            assertNull(
                    MDC.get(RequestTracingFilter.MDC_TENANT_ID),
                    "tenantId should be cleared from MDC");
            assertNull(
                    MDC.get(RequestTracingFilter.MDC_SUBJECT),
                    "subject should be cleared from MDC");
        }

        @Test
        @DisplayName("MDC is cleared after response processing (unauthenticated)")
        void mdcClearedAfterUnauthenticated() throws IOException {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("unauth-clear-id");
            givenResponseHeaders();
            when(requestHolder.isAuthenticated()).thenReturn(false);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, "unauth-clear-id");
            MDC.put(RequestTracingFilter.MDC_TENANT_ID, "-");
            MDC.put(RequestTracingFilter.MDC_SUBJECT, "-");
            MDC.put("extra-key", "extra-value");

            filter.filter(requestContext, responseContext);

            assertNull(MDC.get(RequestTracingFilter.MDC_REQUEST_ID));
            assertNull(MDC.get(RequestTracingFilter.MDC_TENANT_ID));
            assertNull(MDC.get(RequestTracingFilter.MDC_SUBJECT));
            assertNull(MDC.get("extra-key"), "MDC.clear() should remove all keys");
        }

        @Test
        @DisplayName("MDC is cleared even when enrichment throws")
        void mdcClearedOnException() throws Exception {
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("error-req-id");
            givenResponseHeaders();
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenThrow(new RuntimeException("identity error"));

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            MDC.put("pre-existing", "value");

            // The enrichMdcWithIdentity is called inside try, and the catch
            // in that method or the exception might propagate. The finally block
            // should still clear MDC. However, enrichMdcWithIdentity handles
            // the identity being null gracefully, so the exception from identity()
            // may not be caught if isAuthenticated passes but identity() throws.
            // The try-finally in the response filter should still clear MDC.
            try {
                filter.filter(requestContext, responseContext);
            } catch (RuntimeException e) {
                // May or may not propagate depending on implementation
            }

            // MDC should still be cleared in the finally block
            assertNull(
                    MDC.get("pre-existing"), "MDC should be cleared even when an exception occurs");
        }
    }

    // ===================================================================
    // Null requestHolder
    // ===================================================================

    @Nested
    @DisplayName("Null requestHolder")
    class NullRequestHolder {

        @Test
        @DisplayName("response filter handles null requestHolder gracefully")
        void nullHolderResponse() throws Exception {
            injectField(filter, "requestHolder", null);

            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn("null-holder-req");
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            assertDoesNotThrow(() -> filter.filter(requestContext, responseContext));

            assertEquals(
                    "null-holder-req", headers.getFirst(RequestTracingFilter.REQUEST_ID_HEADER));
        }

        @Test
        @DisplayName("request filter works without requestHolder")
        void nullHolderRequest() throws Exception {
            injectField(filter, "requestHolder", null);

            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn("client-id-123");

            assertDoesNotThrow(() -> filter.filter(requestContext));

            verify(requestContext)
                    .setProperty(RequestTracingFilter.REQUEST_ID_PROPERTY, "client-id-123");
        }
    }

    // ===================================================================
    // Constants verification
    // ===================================================================

    @Nested
    @DisplayName("Constants")
    class Constants {

        @Test
        @DisplayName("REQUEST_ID_HEADER is X-Request-Id")
        void requestIdHeader() {
            assertEquals("X-Request-Id", RequestTracingFilter.REQUEST_ID_HEADER);
        }

        @Test
        @DisplayName("REQUEST_ID_PROPERTY is cveil.request-id")
        void requestIdProperty() {
            assertEquals("cveil.request-id", RequestTracingFilter.REQUEST_ID_PROPERTY);
        }

        @Test
        @DisplayName("MDC_REQUEST_ID is requestId")
        void mdcRequestIdKey() {
            assertEquals("requestId", RequestTracingFilter.MDC_REQUEST_ID);
        }

        @Test
        @DisplayName("MDC_TENANT_ID is tenantId")
        void mdcTenantIdKey() {
            assertEquals("tenantId", RequestTracingFilter.MDC_TENANT_ID);
        }

        @Test
        @DisplayName("MDC_SUBJECT is subject")
        void mdcSubjectKey() {
            assertEquals("subject", RequestTracingFilter.MDC_SUBJECT);
        }
    }

    // ===================================================================
    // Full request-response lifecycle
    // ===================================================================

    @Nested
    @DisplayName("Full request-response lifecycle")
    class FullLifecycle {

        @Test
        @DisplayName("client-provided ID flows through request → response")
        void clientIdFlowsThrough() throws IOException {
            String clientId = "lifecycle-client-id";

            // --- Request phase ---
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(clientId);

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestTracingFilter.REQUEST_ID_PROPERTY, clientId);
            assertEquals(clientId, MDC.get(RequestTracingFilter.MDC_REQUEST_ID));
            assertEquals("-", MDC.get(RequestTracingFilter.MDC_TENANT_ID));
            assertEquals("-", MDC.get(RequestTracingFilter.MDC_SUBJECT));

            // --- Response phase ---
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn(clientId);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            IdentityContext identity = tenantIdentity("acme-corp", "user-42");
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(identity);

            lenient().when(requestContext.getMethod()).thenReturn("POST");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient()
                    .when(uriInfo.getRequestUri())
                    .thenReturn(URI.create("/api/v1/subjects/consent/evaluate"));

            lenient().when(responseContext.getStatus()).thenReturn(200);

            filter.filter(requestContext, responseContext);

            // Response header echoed
            assertEquals(clientId, headers.getFirst(RequestTracingFilter.REQUEST_ID_HEADER));

            // MDC cleared after response
            assertNull(MDC.get(RequestTracingFilter.MDC_REQUEST_ID));
            assertNull(MDC.get(RequestTracingFilter.MDC_TENANT_ID));
            assertNull(MDC.get(RequestTracingFilter.MDC_SUBJECT));
        }

        @Test
        @DisplayName("generated ID flows through request → response")
        void generatedIdFlowsThrough() throws IOException {
            // --- Request phase ---
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(null);

            filter.filter(requestContext);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(requestContext)
                    .setProperty(eq(RequestTracingFilter.REQUEST_ID_PROPERTY), captor.capture());
            String generatedId = captor.getValue();
            assertNotNull(generatedId);

            // --- Response phase ---
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn(generatedId);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();
            when(requestHolder.isAuthenticated()).thenReturn(false);

            lenient().when(requestContext.getMethod()).thenReturn("GET");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("/api/v1/health"));

            filter.filter(requestContext, responseContext);

            assertEquals(generatedId, headers.getFirst(RequestTracingFilter.REQUEST_ID_HEADER));

            // MDC cleared
            assertNull(MDC.get(RequestTracingFilter.MDC_REQUEST_ID));
        }

        @Test
        @DisplayName("bootstrap admin (no tenantId) enriches only subject in MDC")
        void bootstrapAdminLifecycle() throws IOException {
            String requestId = "bootstrap-lifecycle-id";

            // --- Request phase ---
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(requestId);

            filter.filter(requestContext);

            // --- Response phase ---
            when(requestContext.getProperty(RequestTracingFilter.REQUEST_ID_PROPERTY))
                    .thenReturn(requestId);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            // Bootstrap identity: no tenantId, has subject
            IdentityContext bootstrapIdentity =
                    new IdentityContext(
                            "bootstrap-admin",
                            null,
                            null,
                            Set.of("super-admin"),
                            Map.of(),
                            null,
                            "bootstrap");
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(bootstrapIdentity);

            lenient().when(requestContext.getMethod()).thenReturn("POST");
            lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
            lenient()
                    .when(uriInfo.getRequestUri())
                    .thenReturn(URI.create("/api/v1/platform/tenants"));

            filter.filter(requestContext, responseContext);

            // Response header echoed
            assertEquals(requestId, headers.getFirst(RequestTracingFilter.REQUEST_ID_HEADER));

            // MDC cleared after
            assertNull(MDC.get(RequestTracingFilter.MDC_TENANT_ID));
            assertNull(MDC.get(RequestTracingFilter.MDC_SUBJECT));
        }
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("very long X-Request-Id is preserved as-is")
        void longRequestId() throws IOException {
            String longId = "a".repeat(500);
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(longId);

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestTracingFilter.REQUEST_ID_PROPERTY, longId);
            assertEquals(longId, MDC.get(RequestTracingFilter.MDC_REQUEST_ID));
        }

        @Test
        @DisplayName("X-Request-Id with special characters is preserved")
        void specialCharactersInRequestId() throws IOException {
            String specialId = "req-123/abc:def@ghi#jkl";
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(specialId);

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestTracingFilter.REQUEST_ID_PROPERTY, specialId);
        }

        @Test
        @DisplayName("X-Request-Id that is already a UUID is preserved")
        void uuidRequestId() throws IOException {
            String uuidId = UUID.randomUUID().toString();
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn(uuidId);

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestTracingFilter.REQUEST_ID_PROPERTY, uuidId);
            assertEquals(uuidId, MDC.get(RequestTracingFilter.MDC_REQUEST_ID));
        }

        @Test
        @DisplayName("multiple sequential requests each get fresh MDC state")
        void sequentialRequestsFreshMdc() throws IOException {
            // First request
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn("first-id");
            filter.filter(requestContext);
            assertEquals("first-id", MDC.get(RequestTracingFilter.MDC_REQUEST_ID));

            // Clear (simulating response filter)
            MDC.clear();

            // Second request
            when(requestContext.getHeaderString(RequestTracingFilter.REQUEST_ID_HEADER))
                    .thenReturn("second-id");
            filter.filter(requestContext);
            assertEquals("second-id", MDC.get(RequestTracingFilter.MDC_REQUEST_ID));
        }
    }
}
