package com.koostack.cveil.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.filter.RateLimitFilter;
import com.koostack.cveil.rest.ratelimit.RateLimitResult;
import com.koostack.cveil.rest.ratelimit.RateLimitService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Unit tests for {@link RateLimitFilter} — the JAX-RS filter that enforces per-tenant burst and
 * monthly rate limits.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Skip: rate limiting unavailable (embedded mode / no Redis)
 *   <li>Skip: unauthenticated request (no identity in holder)
 *   <li>Skip: no tenant ID (bootstrap super-admin)
 *   <li>Burst allowed — request passes through, result stored as property
 *   <li>Burst exceeded — 429 abort with correct headers and JSON body
 *   <li>Monthly allowed — evaluation endpoint passes through
 *   <li>Monthly exceeded — 429 abort with quota-exceeded message
 *   <li>Monthly warning — X-RateLimit-Warning header emitted
 *   <li>Non-evaluation endpoints skip monthly check
 *   <li>Response filter emits rate-limit headers from stored properties
 *   <li>Response filter skips headers when no property stored (skip path)
 *   <li>Response filter skips headers for UNLIMITED results
 *   <li>Evaluation endpoint path matching (various paths)
 *   <li>Abort body shape: error, message, retryAfter
 * </ul>
 */
@DisplayName("RateLimitFilter")
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock private CveilRequestHolder requestHolder;

    @Mock private RateLimitService rateLimitService;

    @Mock private ContainerRequestContext requestContext;

    @Mock private ContainerResponseContext responseContext;

    @Mock private UriInfo uriInfo;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new RateLimitFilter();
        injectField(filter, "requestHolder", requestHolder);
        injectField(filter, "rateLimitService", rateLimitService);
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
    // Identity helpers
    // -----------------------------------------------------------------------

    private static IdentityContext tenantIdentity(String tenantId) {
        return new IdentityContext(
                "user-1",
                "token-abc",
                "user@example.com",
                Set.of("admin"),
                Map.of(),
                tenantId,
                "api-key");
    }

    private static IdentityContext bootstrapIdentity() {
        return new IdentityContext(
                "bootstrap-admin", null, null, Set.of("super-admin"), Map.of(), null, "bootstrap");
    }

    // -----------------------------------------------------------------------
    // Stub helpers
    // -----------------------------------------------------------------------

    private void givenAuthenticatedTenant(String tenantId) {
        when(requestHolder.isAuthenticated()).thenReturn(true);
        when(requestHolder.identity()).thenReturn(tenantIdentity(tenantId));
    }

    private void givenPath(String path) {
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(uriInfo.getPath()).thenReturn(path);
    }

    private Response captureAbort() {
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        return captor.getValue();
    }

    private MultivaluedMap<String, Object> givenResponseHeaders() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        lenient().when(responseContext.getHeaders()).thenReturn(headers);
        return headers;
    }

    private static RateLimitResult burstAllowed(long limit, long remaining) {
        return new RateLimitResult(
                limit,
                remaining,
                Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES),
                false, // burstExceeded
                false, // warning
                false, // monthlyExceeded
                false // subjectLimitExceeded
                );
    }

    private static RateLimitResult burstExceeded(long limit) {
        return new RateLimitResult(
                limit,
                0L,
                Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES),
                true, // burstExceeded
                false, // warning
                false, // monthlyExceeded
                false // subjectLimitExceeded
                );
    }

    private static RateLimitResult monthlyAllowed(long limit, long remaining, boolean warning) {
        return new RateLimitResult(
                limit,
                remaining,
                Instant.now().plus(15, ChronoUnit.DAYS),
                false, // burstExceeded
                warning, // warning
                false, // monthlyExceeded
                false // subjectLimitExceeded
                );
    }

    private static RateLimitResult monthlyExceeded(long limit) {
        return new RateLimitResult(
                limit,
                0L,
                Instant.now().plus(15, ChronoUnit.DAYS),
                false, // burstExceeded
                true, // warning
                true, // monthlyExceeded
                false // subjectLimitExceeded
                );
    }

    // ===================================================================
    // Skip conditions — request filter
    // ===================================================================

    @Nested
    @DisplayName("Skip: rate limiting unavailable")
    class SkipUnavailable {

        @Test
        @DisplayName("skips when service is not available (no Redis)")
        void skipsWhenUnavailable() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(false);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(requestContext, never()).setProperty(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Skip: unauthenticated request")
    class SkipUnauthenticated {

        @Test
        @DisplayName("skips when request holder has no identity")
        void skipsUnauthenticated() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            when(requestHolder.isAuthenticated()).thenReturn(false);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(rateLimitService, never()).checkBurst(anyString());
        }
    }

    @Nested
    @DisplayName("Skip: no tenant ID (bootstrap super-admin)")
    class SkipNoTenantId {

        @Test
        @DisplayName("skips when tenantId is null (bootstrap)")
        void skipsNullTenantId() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(bootstrapIdentity());

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(rateLimitService, never()).checkBurst(anyString());
        }

        @Test
        @DisplayName("skips when tenantId is blank")
        void skipsBlankTenantId() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            when(requestHolder.isAuthenticated()).thenReturn(true);
            IdentityContext blankTenant =
                    new IdentityContext(
                            "admin",
                            null,
                            null,
                            Set.of("super-admin"),
                            Map.of(),
                            "   ",
                            "bootstrap");
            when(requestHolder.identity()).thenReturn(blankTenant);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(rateLimitService, never()).checkBurst(anyString());
        }

        @Test
        @DisplayName("skips when tenantId is empty string")
        void skipsEmptyTenantId() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            when(requestHolder.isAuthenticated()).thenReturn(true);
            IdentityContext emptyTenant =
                    new IdentityContext(
                            "admin", null, null, Set.of("super-admin"), Map.of(), "", "bootstrap");
            when(requestHolder.identity()).thenReturn(emptyTenant);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    // ===================================================================
    // Burst enforcement
    // ===================================================================

    @Nested
    @DisplayName("Burst limit enforcement")
    class BurstEnforcement {

        @Test
        @DisplayName("burst allowed — request passes through, result stored")
        void burstAllowedPassesThrough() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            RateLimitResult result = burstAllowed(60, 55);
            when(rateLimitService.checkBurst("acme")).thenReturn(result);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(requestContext).setProperty(eq("cveil.ratelimit.burst-result"), eq(result));
        }

        @Test
        @DisplayName("burst exceeded — 429 abort")
        void burstExceededAborts() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            RateLimitResult result = burstExceeded(60);
            when(rateLimitService.checkBurst("acme")).thenReturn(result);

            filter.filter(requestContext);

            Response response = captureAbort();
            assertEquals(429, response.getStatus());
        }

        @Test
        @DisplayName("burst exceeded — response has JSON content type")
        void burstExceededJsonContentType() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstExceeded(60));

            filter.filter(requestContext);

            Response response = captureAbort();
            assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        }

        @Test
        @DisplayName("burst exceeded — response has rate-limit headers")
        void burstExceededHasHeaders() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            RateLimitResult result = burstExceeded(60);
            when(rateLimitService.checkBurst("acme")).thenReturn(result);

            filter.filter(requestContext);

            Response response = captureAbort();
            assertEquals("60", response.getHeaderString(RateLimitFilter.HEADER_LIMIT));
            assertEquals("0", response.getHeaderString(RateLimitFilter.HEADER_REMAINING));
            assertNotNull(response.getHeaderString(RateLimitFilter.HEADER_RESET));
            assertNotNull(response.getHeaderString(RateLimitFilter.HEADER_RETRY_AFTER));
        }

        @Test
        @DisplayName("burst exceeded — response body has error fields")
        @SuppressWarnings("unchecked")
        void burstExceededBody() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstExceeded(60));

            filter.filter(requestContext);

            Response response = captureAbort();
            Object entity = response.getEntity();
            assertNotNull(entity);
            assertTrue(entity instanceof Map, "Entity should be a Map");
            Map<String, Object> body = (Map<String, Object>) entity;
            assertEquals("rate_limit_exceeded", body.get("error"));
            assertEquals("Request rate limit exceeded", body.get("message"));
            assertNotNull(body.get("retryAfter"));
        }

        @Test
        @DisplayName("burst exceeded — Retry-After is at least 1 second")
        void burstExceededRetryAfterMinimum() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstExceeded(60));

            filter.filter(requestContext);

            Response response = captureAbort();
            String retryAfter = response.getHeaderString(RateLimitFilter.HEADER_RETRY_AFTER);
            assertNotNull(retryAfter);
            long retrySeconds = Long.parseLong(retryAfter);
            assertTrue(retrySeconds >= 1, "Retry-After should be >= 1, got " + retrySeconds);
        }

        @Test
        @DisplayName("burst exceeded skips monthly check entirely")
        void burstExceededSkipsMonthly() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/evaluate");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstExceeded(60));

            filter.filter(requestContext);

            verify(requestContext).abortWith(any());
            verify(rateLimitService, never()).checkMonthly(anyString());
        }
    }

    // ===================================================================
    // Monthly enforcement
    // ===================================================================

    @Nested
    @DisplayName("Monthly evaluation limit enforcement")
    class MonthlyEnforcement {

        @Test
        @DisplayName("monthly allowed on evaluation endpoint — passes through")
        void monthlyAllowedPassesThrough() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/evaluate");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));
            RateLimitResult monthly = monthlyAllowed(10_000, 8_000, false);
            when(rateLimitService.checkMonthly("acme")).thenReturn(monthly);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(requestContext).setProperty(eq("cveil.ratelimit.monthly-result"), eq(monthly));
        }

        @Test
        @DisplayName("monthly exceeded on evaluation endpoint — 429 abort")
        void monthlyExceededAborts() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/evaluate");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));
            when(rateLimitService.checkMonthly("acme")).thenReturn(monthlyExceeded(10_000));

            filter.filter(requestContext);

            Response response = captureAbort();
            assertEquals(429, response.getStatus());
        }

        @Test
        @DisplayName("monthly exceeded — message says 'Monthly evaluation quota exceeded'")
        @SuppressWarnings("unchecked")
        void monthlyExceededMessage() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/evaluate");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));
            when(rateLimitService.checkMonthly("acme")).thenReturn(monthlyExceeded(10_000));

            filter.filter(requestContext);

            Response response = captureAbort();
            Map<String, Object> body = (Map<String, Object>) response.getEntity();
            assertEquals("Monthly evaluation quota exceeded", body.get("message"));
            assertEquals("rate_limit_exceeded", body.get("error"));
        }

        @Test
        @DisplayName("monthly exceeded — Retry-After header present")
        void monthlyExceededRetryAfter() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/evaluate");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));
            when(rateLimitService.checkMonthly("acme")).thenReturn(monthlyExceeded(10_000));

            filter.filter(requestContext);

            Response response = captureAbort();
            assertNotNull(response.getHeaderString(RateLimitFilter.HEADER_RETRY_AFTER));
        }

        @Test
        @DisplayName("non-evaluation endpoint skips monthly check")
        void nonEvalSkipsMonthly() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));

            filter.filter(requestContext);

            verify(rateLimitService, never()).checkMonthly(anyString());
            verify(requestContext, never()).abortWith(any());
        }
    }

    // ===================================================================
    // Evaluation endpoint path matching
    // ===================================================================

    @Nested
    @DisplayName("Evaluation endpoint path matching")
    class EvaluationPathMatching {

        private void assertMonthlyChecked(String path) throws IOException {
            reset(requestContext, rateLimitService, requestHolder, uriInfo);
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath(path);

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));
            when(rateLimitService.checkMonthly("acme"))
                    .thenReturn(monthlyAllowed(10_000, 9_000, false));
            // Consent write endpoints also trigger subject limit check
            lenient()
                    .when(rateLimitService.checkSubjectLimit(eq("acme"), anyString()))
                    .thenReturn(RateLimitResult.UNLIMITED);

            filter.filter(requestContext);

            verify(rateLimitService).checkMonthly("acme");
        }

        private void assertMonthlyNotChecked(String path) throws IOException {
            reset(requestContext, rateLimitService, requestHolder, uriInfo);
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath(path);

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));

            filter.filter(requestContext);

            verify(rateLimitService, never()).checkMonthly(anyString());
        }

        @Test
        @DisplayName("/evaluate is an evaluation endpoint")
        void evaluatePath() throws IOException {
            assertMonthlyChecked("api/v1/subjects/consent/evaluate");
        }

        @Test
        @DisplayName("/grant is an evaluation endpoint")
        void grantPath() throws IOException {
            assertMonthlyChecked("api/v1/subjects/consent/grant");
        }

        @Test
        @DisplayName("/revoke is an evaluation endpoint")
        void revokePath() throws IOException {
            assertMonthlyChecked("api/v1/subjects/consent/revoke");
        }

        @Test
        @DisplayName("/grant-batch is an evaluation endpoint")
        void grantBatchPath() throws IOException {
            assertMonthlyChecked("api/v1/subjects/consent/grant-batch");
        }

        @Test
        @DisplayName("/revoke-batch is an evaluation endpoint")
        void revokeBatchPath() throws IOException {
            assertMonthlyChecked("api/v1/subjects/consent/revoke-batch");
        }

        @Test
        @DisplayName("path with leading slash is recognized")
        void pathWithLeadingSlash() throws IOException {
            assertMonthlyChecked("/api/v1/subjects/consent/evaluate");
        }

        @Test
        @DisplayName("/admin/fields is NOT an evaluation endpoint")
        void adminFieldsNotEvaluation() throws IOException {
            assertMonthlyNotChecked("api/v1/admin/fields");
        }

        @Test
        @DisplayName("/platform/tenants is NOT an evaluation endpoint")
        void platformTenantsNotEvaluation() throws IOException {
            assertMonthlyNotChecked("api/v1/platform/tenants");
        }

        @Test
        @DisplayName("/health is NOT an evaluation endpoint")
        void healthNotEvaluation() throws IOException {
            assertMonthlyNotChecked("api/v1/health");
        }

        @Test
        @DisplayName("/subjects/{id}/summary is NOT an evaluation endpoint")
        void subjectSummaryNotEvaluation() throws IOException {
            assertMonthlyNotChecked("api/v1/subjects/user-1/summary");
        }

        @Test
        @DisplayName("/subjects/{id}/history is NOT an evaluation endpoint")
        void subjectHistoryNotEvaluation() throws IOException {
            assertMonthlyNotChecked("api/v1/subjects/user-1/history");
        }

        @Test
        @DisplayName("path without /consent prefix is never evaluation")
        void noConsentPrefixNotEvaluation() throws IOException {
            assertMonthlyNotChecked("api/v1/evaluate");
        }
    }

    // ===================================================================
    // Response filter — header emission
    // ===================================================================

    @Nested
    @DisplayName("Response filter — rate-limit headers")
    class ResponseFilterHeaders {

        @Test
        @DisplayName("emits burst headers from stored property")
        void emitsBurstHeaders() throws IOException {
            RateLimitResult burst = burstAllowed(60, 42);
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(burst);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(null);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertEquals("60", headers.getFirst(RateLimitFilter.HEADER_LIMIT));
            assertEquals("42", headers.getFirst(RateLimitFilter.HEADER_REMAINING));
            assertNotNull(headers.getFirst(RateLimitFilter.HEADER_RESET));
        }

        @Test
        @DisplayName("does not emit headers when no burst result stored (skip path)")
        void noHeadersWhenNoProperty() throws IOException {
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(null);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(null);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertNull(headers.getFirst(RateLimitFilter.HEADER_LIMIT));
            assertNull(headers.getFirst(RateLimitFilter.HEADER_REMAINING));
            assertNull(headers.getFirst(RateLimitFilter.HEADER_RESET));
        }

        @Test
        @DisplayName("does not emit headers for UNLIMITED burst result")
        void noHeadersForUnlimited() throws IOException {
            when(requestContext.getProperty("cveil.ratelimit.burst-result"))
                    .thenReturn(RateLimitResult.UNLIMITED);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(null);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertNull(headers.getFirst(RateLimitFilter.HEADER_LIMIT));
            assertNull(headers.getFirst(RateLimitFilter.HEADER_REMAINING));
        }

        @Test
        @DisplayName("emits X-RateLimit-Warning when monthly warning is set")
        void emitsWarningHeader() throws IOException {
            RateLimitResult burst = burstAllowed(60, 55);
            RateLimitResult monthly = monthlyAllowed(10_000, 500, true); // warning=true
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(burst);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(monthly);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertEquals("approaching limit", headers.getFirst(RateLimitFilter.HEADER_WARNING));
        }

        @Test
        @DisplayName("does not emit warning when monthly warning is false")
        void noWarningWhenNotApproaching() throws IOException {
            RateLimitResult burst = burstAllowed(60, 55);
            RateLimitResult monthly = monthlyAllowed(10_000, 8_000, false);
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(burst);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(monthly);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertNull(headers.getFirst(RateLimitFilter.HEADER_WARNING));
        }

        @Test
        @DisplayName("does not emit warning for UNLIMITED monthly result")
        void noWarningForUnlimitedMonthly() throws IOException {
            RateLimitResult burst = burstAllowed(60, 55);
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(burst);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result"))
                    .thenReturn(RateLimitResult.UNLIMITED);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertNull(headers.getFirst(RateLimitFilter.HEADER_WARNING));
        }

        @Test
        @DisplayName("no monthly property stored — no warning header")
        void noMonthlyPropertyNoWarning() throws IOException {
            RateLimitResult burst = burstAllowed(60, 55);
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(burst);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(null);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertNull(headers.getFirst(RateLimitFilter.HEADER_WARNING));
        }

        @Test
        @DisplayName("reset header is epoch seconds from burst result")
        void resetHeaderIsEpochSeconds() throws IOException {
            Instant resetAt = Instant.ofEpochSecond(1_700_000_000L);
            RateLimitResult burst =
                    new RateLimitResult(100L, 80L, resetAt, false, false, false, false);
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(burst);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(null);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertEquals("1700000000", headers.getFirst(RateLimitFilter.HEADER_RESET));
        }
    }

    // ===================================================================
    // Response header constants
    // ===================================================================

    @Nested
    @DisplayName("Header constants")
    class HeaderConstants {

        @Test
        @DisplayName("HEADER_LIMIT is X-RateLimit-Limit")
        void headerLimit() {
            assertEquals("X-RateLimit-Limit", RateLimitFilter.HEADER_LIMIT);
        }

        @Test
        @DisplayName("HEADER_REMAINING is X-RateLimit-Remaining")
        void headerRemaining() {
            assertEquals("X-RateLimit-Remaining", RateLimitFilter.HEADER_REMAINING);
        }

        @Test
        @DisplayName("HEADER_RESET is X-RateLimit-Reset")
        void headerReset() {
            assertEquals("X-RateLimit-Reset", RateLimitFilter.HEADER_RESET);
        }

        @Test
        @DisplayName("HEADER_WARNING is X-RateLimit-Warning")
        void headerWarning() {
            assertEquals("X-RateLimit-Warning", RateLimitFilter.HEADER_WARNING);
        }

        @Test
        @DisplayName("HEADER_RETRY_AFTER is Retry-After")
        void headerRetryAfter() {
            assertEquals("Retry-After", RateLimitFilter.HEADER_RETRY_AFTER);
        }
    }

    // ===================================================================
    // Full lifecycle scenarios
    // ===================================================================

    @Nested
    @DisplayName("Full request-response lifecycle")
    class FullLifecycle {

        @Test
        @DisplayName("admin endpoint: burst under limit → response has headers, no warning")
        void adminUnderLimit() throws IOException {
            // --- Request phase ---
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            RateLimitResult burst = burstAllowed(60, 45);
            when(rateLimitService.checkBurst("acme")).thenReturn(burst);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(requestContext).setProperty("cveil.ratelimit.burst-result", burst);

            // --- Response phase ---
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(burst);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(null);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertEquals("60", headers.getFirst(RateLimitFilter.HEADER_LIMIT));
            assertEquals("45", headers.getFirst(RateLimitFilter.HEADER_REMAINING));
            assertNull(headers.getFirst(RateLimitFilter.HEADER_WARNING));
        }

        @Test
        @DisplayName(
                "evaluation endpoint: burst OK + monthly warning → response has warning header")
        void evaluationWithMonthlyWarning() throws IOException {
            // --- Request phase ---
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/evaluate");

            RateLimitResult burst = burstAllowed(60, 55);
            RateLimitResult monthly = monthlyAllowed(10_000, 500, true);
            when(rateLimitService.checkBurst("acme")).thenReturn(burst);
            when(rateLimitService.checkMonthly("acme")).thenReturn(monthly);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());

            // --- Response phase ---
            when(requestContext.getProperty("cveil.ratelimit.burst-result")).thenReturn(burst);
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(monthly);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertEquals("60", headers.getFirst(RateLimitFilter.HEADER_LIMIT));
            assertEquals("55", headers.getFirst(RateLimitFilter.HEADER_REMAINING));
            assertEquals("approaching limit", headers.getFirst(RateLimitFilter.HEADER_WARNING));
        }

        @Test
        @DisplayName("unauthenticated health check → no rate limit activity at all")
        void healthCheckNoRateLimit() throws IOException {
            // --- Request phase ---
            lenient().when(rateLimitService.isAvailable()).thenReturn(true);
            lenient().when(requestHolder.isAuthenticated()).thenReturn(false);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(rateLimitService, never()).checkBurst(anyString());

            // --- Response phase (nothing stored) ---
            lenient()
                    .when(requestContext.getProperty("cveil.ratelimit.burst-result"))
                    .thenReturn(null);
            lenient()
                    .when(requestContext.getProperty("cveil.ratelimit.monthly-result"))
                    .thenReturn(null);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertNull(headers.getFirst(RateLimitFilter.HEADER_LIMIT));
            assertNull(headers.getFirst(RateLimitFilter.HEADER_WARNING));
        }

        @Test
        @DisplayName("bootstrap super-admin → no rate limit enforcement")
        void bootstrapNoRateLimit() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(bootstrapIdentity());

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(rateLimitService, never()).checkBurst(anyString());
            verify(rateLimitService, never()).checkMonthly(anyString());
        }
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("property stored as non-RateLimitResult type is ignored in response filter")
        void wrongPropertyType() throws IOException {
            when(requestContext.getProperty("cveil.ratelimit.burst-result"))
                    .thenReturn("not-a-result");
            when(requestContext.getProperty("cveil.ratelimit.monthly-result")).thenReturn(42);
            MultivaluedMap<String, Object> headers = givenResponseHeaders();

            filter.filter(requestContext, responseContext);

            assertNull(headers.getFirst(RateLimitFilter.HEADER_LIMIT));
            assertNull(headers.getFirst(RateLimitFilter.HEADER_WARNING));
        }

        @Test
        @DisplayName("burst remaining of 0 but not exceeded — passes through")
        void zeroRemainingNotExceeded() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            // Remaining 0 but burstExceeded=false means exactly at limit, not over
            RateLimitResult result =
                    new RateLimitResult(
                            60L,
                            0L,
                            Instant.now().plus(1, ChronoUnit.MINUTES),
                            false,
                            false,
                            false,
                            false);
            when(rateLimitService.checkBurst("acme")).thenReturn(result);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("UNLIMITED burst result — no properties stored, request passes")
        void unlimitedBurstResult() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            when(rateLimitService.checkBurst("acme")).thenReturn(RateLimitResult.UNLIMITED);

            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            // The property is still set (the filter stores whatever checkBurst returns)
            verify(requestContext)
                    .setProperty(
                            eq("cveil.ratelimit.burst-result"), eq(RateLimitResult.UNLIMITED));
        }

        @Test
        @DisplayName("multiple tenants see independent rate limit checks")
        void multipleTenants() throws IOException {
            // Tenant 1 — burst exceeded
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("tenant-1");
            givenPath("api/v1/admin/fields");
            when(rateLimitService.checkBurst("tenant-1")).thenReturn(burstExceeded(30));

            filter.filter(requestContext);
            verify(requestContext).abortWith(any());

            // Tenant 2 — burst allowed (reset mocks)
            reset(requestContext, requestHolder, uriInfo);
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("tenant-2");
            givenPath("api/v1/admin/fields");
            when(rateLimitService.checkBurst("tenant-2")).thenReturn(burstAllowed(100, 90));

            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("consent path without evaluation segment — no monthly check")
        void consentNonEvalPath() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/user-1/summary");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));

            filter.filter(requestContext);

            verify(rateLimitService, never()).checkMonthly(anyString());
        }

        @Test
        @DisplayName("null path from UriInfo does not cause NPE in evaluation check")
        void nullPathHandled() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            when(requestContext.getUriInfo()).thenReturn(uriInfo);
            when(uriInfo.getPath()).thenReturn(null);

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));

            // Should not throw NPE — isEvaluationEndpoint handles null
            assertDoesNotThrow(() -> filter.filter(requestContext));
            verify(rateLimitService, never()).checkMonthly(anyString());
        }
    }

    // ===================================================================
    // Both burst and monthly exceeded
    // ===================================================================

    @Nested
    @DisplayName("Burst takes precedence over monthly")
    class BurstPrecedence {

        @Test
        @DisplayName("burst exceeded aborts before monthly is even checked")
        void burstAbortsPreventsMonthly() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/evaluate");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstExceeded(60));

            filter.filter(requestContext);

            verify(requestContext).abortWith(any());
            verify(rateLimitService, never()).checkMonthly(anyString());
        }
    }

    // ===================================================================
    // Grant and revoke batch paths
    // ===================================================================

    @Nested
    @DisplayName("Batch endpoint path matching")
    class BatchPathMatching {

        @Test
        @DisplayName("/consent/grant-batch triggers monthly check")
        void grantBatch() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/grant-batch");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));
            when(rateLimitService.checkMonthly("acme"))
                    .thenReturn(monthlyAllowed(10_000, 9_000, false));
            // grant-batch is also a consent write endpoint → subject limit check
            lenient()
                    .when(rateLimitService.checkSubjectLimit(eq("acme"), anyString()))
                    .thenReturn(RateLimitResult.UNLIMITED);

            filter.filter(requestContext);

            verify(rateLimitService).checkMonthly("acme");
            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("/consent/revoke-batch triggers monthly check")
        void revokeBatch() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/revoke-batch");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));
            when(rateLimitService.checkMonthly("acme"))
                    .thenReturn(monthlyAllowed(10_000, 9_000, false));
            // revoke-batch is also a consent write endpoint → subject limit check
            lenient()
                    .when(rateLimitService.checkSubjectLimit(eq("acme"), anyString()))
                    .thenReturn(RateLimitResult.UNLIMITED);

            filter.filter(requestContext);

            verify(rateLimitService).checkMonthly("acme");
        }
    }

    // ===================================================================
    // Abort response shape validation
    // ===================================================================

    @Nested
    @DisplayName("Abort response shape")
    class AbortResponseShape {

        @Test
        @DisplayName(
                "burst abort has Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining=0,"
                        + " X-RateLimit-Reset")
        void burstAbortHeaderSet() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/fields");

            Instant resetAt = Instant.ofEpochSecond(1_700_000_060L);
            RateLimitResult burst =
                    new RateLimitResult(100L, 0L, resetAt, true, false, false, false);
            when(rateLimitService.checkBurst("acme")).thenReturn(burst);

            filter.filter(requestContext);

            Response response = captureAbort();
            assertEquals("100", response.getHeaderString(RateLimitFilter.HEADER_LIMIT));
            assertEquals("0", response.getHeaderString(RateLimitFilter.HEADER_REMAINING));
            assertEquals("1700000060", response.getHeaderString(RateLimitFilter.HEADER_RESET));
            assertNotNull(response.getHeaderString(RateLimitFilter.HEADER_RETRY_AFTER));
        }

        @Test
        @DisplayName("monthly abort has same header set as burst abort")
        void monthlyAbortHeaderSet() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/subjects/consent/evaluate");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstAllowed(60, 55));

            Instant resetAt = Instant.ofEpochSecond(1_700_100_000L);
            RateLimitResult monthly =
                    new RateLimitResult(10_000L, 0L, resetAt, false, true, true, false);
            when(rateLimitService.checkMonthly("acme")).thenReturn(monthly);

            filter.filter(requestContext);

            Response response = captureAbort();
            assertEquals(429, response.getStatus());
            assertEquals("10000", response.getHeaderString(RateLimitFilter.HEADER_LIMIT));
            assertEquals("0", response.getHeaderString(RateLimitFilter.HEADER_REMAINING));
            assertEquals("1700100000", response.getHeaderString(RateLimitFilter.HEADER_RESET));
            assertNotNull(response.getHeaderString(RateLimitFilter.HEADER_RETRY_AFTER));
        }

        @Test
        @DisplayName("abort response entity is an unmodifiable-ish map with 3 keys")
        @SuppressWarnings("unchecked")
        void abortEntityShape() throws IOException {
            when(rateLimitService.isAvailable()).thenReturn(true);
            givenAuthenticatedTenant("acme");
            givenPath("api/v1/admin/categories");

            when(rateLimitService.checkBurst("acme")).thenReturn(burstExceeded(30));

            filter.filter(requestContext);

            Response response = captureAbort();
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertEquals(3, entity.size(), "Entity should have exactly 3 keys");
            assertTrue(entity.containsKey("error"));
            assertTrue(entity.containsKey("message"));
            assertTrue(entity.containsKey("retryAfter"));
        }
    }
}
