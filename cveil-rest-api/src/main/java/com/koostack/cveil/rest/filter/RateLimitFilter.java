package com.koostack.cveil.rest.filter;

import com.koostack.cveil.core.metrics.Metric;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.metrics.MetricType;
import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.ratelimit.RateLimitResult;
import com.koostack.cveil.rest.ratelimit.RateLimitService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS filter that enforces per-tenant rate limits on every authenticated request processed by
 * the Cveil REST API.
 *
 * <p>This filter implements both {@link ContainerRequestFilter} (to check and enforce limits on the
 * request side) and {@link ContainerResponseFilter} (to attach rate-limit response headers on every
 * response).
 *
 * <h2>Rate Limiting Dimensions</h2>
 *
 * <ol>
 *   <li><strong>Burst limit</strong> (all endpoints) - per-minute request cap based on the tenant's
 *       {@code burst_limit_per_min} configuration. Enforced via Redis {@code INCR} on a per-minute
 *       key.
 *   <li><strong>Monthly evaluation limit</strong> (evaluation endpoints only) - per-calendar-month
 *       cap on consent evaluations, based on the tenant's subscription plan ({@code
 *       eval_limit_monthly}). Enforced via Redis {@code INCR} on a per-month key.
 * </ol>
 *
 * <h2>Response Headers</h2>
 *
 * <table>
 *   <tr><th>Header</th><th>Description</th></tr>
 *   <tr><td>{@code X-RateLimit-Limit}</td>
 *       <td>The burst limit for the current window (requests/minute)</td></tr>
 *   <tr><td>{@code X-RateLimit-Remaining}</td>
 *       <td>Remaining requests in the current burst window</td></tr>
 *   <tr><td>{@code X-RateLimit-Reset}</td>
 *       <td>Unix epoch seconds when the burst window resets</td></tr>
 *   <tr><td>{@code X-RateLimit-Warning}</td>
 *       <td>Set to {@code "approaching limit"} when monthly usage reaches
 *           90% of the evaluation quota</td></tr>
 *   <tr><td>{@code Retry-After}</td>
 *       <td>Seconds until the rate-limit window resets (only on 429
 *           responses)</td></tr>
 * </table>
 *
 * <h2>Priority and Ordering</h2>
 *
 * <p>This filter runs at {@link Priorities#AUTHORIZATION} priority (2000), which is
 * <strong>after</strong> the authentication filter ({@link Priorities#AUTHENTICATION} = 1000) and
 * the tracing filter (900). By the time this filter executes, the {@link CveilRequestHolder} is
 * already populated with the authenticated {@link IdentityContext} including the tenant ID.
 *
 * <h2>Skip Conditions</h2>
 *
 * <ul>
 *   <li><strong>Unauthenticated requests</strong> - public endpoints (health checks) that bypass
 *       the auth filter have no identity in the holder; rate limiting is skipped.
 *   <li><strong>No tenant</strong> - bootstrap super-admin requests have no tenant ID; rate
 *       limiting is skipped (the super-admin operates at platform level, not under a tenant quota).
 *   <li><strong>Rate limiting unavailable</strong> - if Redis is not configured (embedded mode) or
 *       the platform admin service is absent, the service reports itself as unavailable and the
 *       filter skips enforcement (fail-open).
 * </ul>
 *
 * @see RateLimitService
 * @see RateLimitResult
 * @see ApiKeyAuthFilter
 * @see RequestTracingFilter
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class RateLimitFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    // -----------------------------------------------------------------------
    // Header constants
    // -----------------------------------------------------------------------

    /** Response header: maximum requests allowed in the current window. */
    public static final String HEADER_LIMIT = "X-RateLimit-Limit";

    /** Response header: remaining requests in the current window. */
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";

    /** Response header: Unix epoch seconds when the window resets. */
    public static final String HEADER_RESET = "X-RateLimit-Reset";

    /** Response header: warning when approaching monthly quota. */
    public static final String HEADER_WARNING = "X-RateLimit-Warning";

    /** Standard response header: seconds until the client should retry. */
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    /** Warning header value when monthly usage reaches 90%. */
    private static final String WARNING_APPROACHING = "approaching limit";

    // -----------------------------------------------------------------------
    // Request property keys (used to pass state to response filter)
    // -----------------------------------------------------------------------

    /**
     * Request property key for the burst rate-limit result. Set during the request phase so the
     * response filter can emit headers without re-querying Redis.
     */
    private static final String PROP_BURST_RESULT = "cveil.ratelimit.burst-result";

    /** Request property key for the monthly rate-limit result (evaluation endpoints only). */
    private static final String PROP_MONTHLY_RESULT = "cveil.ratelimit.monthly-result";

    /** Request property key for the subject-limit result (consent write endpoints only). */
    private static final String PROP_SUBJECT_RESULT = "cveil.ratelimit.subject-result";

    // -----------------------------------------------------------------------
    // Evaluation endpoint paths (monthly counter applies to these)
    // -----------------------------------------------------------------------

    /**
     * URI path segments that identify consent evaluation endpoints. The monthly evaluation counter
     * is incremented only for requests matching these paths.
     */
    private static final Set<String> EVALUATION_PATH_SEGMENTS =
            Set.of("/evaluate", "/grant", "/revoke", "/grant-batch", "/revoke-batch");

    /**
     * URI path segments for consent write endpoints where the subject limit should be enforced
     * (grant and revoke operations).
     */
    private static final Set<String> CONSENT_WRITE_PATH_SEGMENTS =
            Set.of("/grant", "/revoke", "/grant-batch", "/revoke-batch");

    // -----------------------------------------------------------------------
    // Injected dependencies
    // -----------------------------------------------------------------------

    @Inject CveilRequestHolder requestHolder;

    @Inject RateLimitService rateLimitService;

    @Inject
    @SuppressWarnings("rawtypes")
    jakarta.enterprise.inject.Instance<MetricPublisherService> metricPublisherInstance;

    // -----------------------------------------------------------------------
    // Request filter - enforce rate limits
    // -----------------------------------------------------------------------

    /**
     * Checks burst and (for evaluation endpoints) monthly rate limits for the authenticated tenant.
     *
     * <p>If a limit is exceeded, the request is aborted with HTTP 429 (Too Many Requests) and
     * appropriate headers. Otherwise, the rate-limit results are stored as request properties for
     * the response filter to emit headers.
     *
     * @param ctx the incoming request context
     * @throws IOException never thrown - declared by the interface contract
     */
    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        // Skip if rate limiting is not available (embedded mode, no Redis)
        if (!rateLimitService.isAvailable()) {
            return;
        }

        // Skip unauthenticated requests (public paths, auth failures)
        if (!requestHolder.isAuthenticated()) {
            return;
        }

        IdentityContext identity = requestHolder.identity();
        String tenantId = identity.tenantId();

        // Skip bootstrap super-admin (no tenant, no quota)
        if (tenantId == null || tenantId.isBlank()) {
            LOG.trace("RateLimitFilter: no tenantId (bootstrap/super-admin) - skipping");
            return;
        }

        // ---- Burst limit check ----
        RateLimitResult burstResult = rateLimitService.checkBurst(tenantId);
        ctx.setProperty(PROP_BURST_RESULT, burstResult);

        if (burstResult.burstExceeded()) {
            LOG.info(
                    "RateLimitFilter: burst limit exceeded for tenant '{}' " + "(limit={}/min)",
                    tenantId,
                    burstResult.limit());
            publishRateLimitMetric(tenantId, "burst");
            abortTooManyRequests(ctx, burstResult);
            return;
        }

        // ---- Monthly evaluation limit check (evaluation endpoints only) ----
        String path = ctx.getUriInfo().getPath();
        if (isEvaluationEndpoint(path)) {
            RateLimitResult monthlyResult = rateLimitService.checkMonthly(tenantId);
            ctx.setProperty(PROP_MONTHLY_RESULT, monthlyResult);

            if (monthlyResult.monthlyExceeded()) {
                LOG.info(
                        "RateLimitFilter: monthly evaluation limit exceeded "
                                + "for tenant '{}' (limit={}/month)",
                        tenantId,
                        monthlyResult.limit());
                publishRateLimitMetric(tenantId, "monthly");
                abortTooManyRequests(ctx, monthlyResult);
                return;
            }
            if (monthlyResult.warning()) {
                publishRateLimitMetric(tenantId, "monthly_warning");
            }
        }

        // ---- Subject limit check (consent write endpoints only) ----
        if (isConsentWriteEndpoint(path)) {
            // Extract subject from the identity context — subject limit
            // is checked per calling subject per billing period
            String subjectId = identity.subject();
            if (subjectId != null && !subjectId.isBlank()) {
                RateLimitResult subjectResult =
                        rateLimitService.checkSubjectLimit(tenantId, subjectId);
                ctx.setProperty(PROP_SUBJECT_RESULT, subjectResult);

                if (subjectResult.subjectLimitExceeded()) {
                    LOG.info(
                            "RateLimitFilter: subject limit exceeded for "
                                    + "tenant '{}' (limit={}/month)",
                            tenantId,
                            subjectResult.limit());
                    publishRateLimitMetric(tenantId, "subject");
                    abortTooManyRequests(ctx, subjectResult);
                    return;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Response filter - emit rate-limit headers
    // -----------------------------------------------------------------------

    /**
     * Attaches rate-limit response headers to every response.
     *
     * <p>Reads the rate-limit results stored as request properties during the request phase and
     * emits the appropriate headers. If a monthly warning threshold has been reached, the {@code
     * X-RateLimit-Warning} header is included.
     *
     * @param requestCtx the request context
     * @param responseCtx the response context
     * @throws IOException never thrown - declared by the interface contract
     */
    @Override
    public void filter(ContainerRequestContext requestCtx, ContainerResponseContext responseCtx)
            throws IOException {
        // Emit burst headers if available
        Object burstObj = requestCtx.getProperty(PROP_BURST_RESULT);
        if (burstObj instanceof RateLimitResult burst && !burst.isUnlimited()) {
            responseCtx.getHeaders().putSingle(HEADER_LIMIT, String.valueOf(burst.limit()));
            responseCtx.getHeaders().putSingle(HEADER_REMAINING, String.valueOf(burst.remaining()));
            responseCtx
                    .getHeaders()
                    .putSingle(HEADER_RESET, String.valueOf(burst.resetAtEpochSeconds()));
        }

        // Emit monthly warning header if approaching limit
        Object monthlyObj = requestCtx.getProperty(PROP_MONTHLY_RESULT);
        if (monthlyObj instanceof RateLimitResult monthly
                && !monthly.isUnlimited()
                && monthly.warning()) {
            responseCtx.getHeaders().putSingle(HEADER_WARNING, WARNING_APPROACHING);
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Determines whether the request path targets a consent evaluation endpoint that should count
     * toward the monthly evaluation quota.
     *
     * @param path the request URI path
     * @return {@code true} if this is an evaluation endpoint
     */
    private static boolean isEvaluationEndpoint(String path) {
        return matchesConsentSegments(path, EVALUATION_PATH_SEGMENTS);
    }

    /**
     * Determines whether the request path targets a consent write endpoint where subject limits
     * should be enforced.
     *
     * @param path the request URI path
     * @return {@code true} if this is a consent write endpoint
     */
    private static boolean isConsentWriteEndpoint(String path) {
        return matchesConsentSegments(path, CONSENT_WRITE_PATH_SEGMENTS);
    }

    /**
     * Checks whether the path matches any of the given path segments under the consent API prefix.
     */
    private static boolean matchesConsentSegments(String path, Set<String> segments) {
        if (path == null) {
            return false;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (!normalized.contains("/consent")) {
            return false;
        }
        for (String segment : segments) {
            if (normalized.endsWith(segment)
                    || normalized.contains(segment + "/")
                    || normalized.contains(segment + "?")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aborts the request with HTTP 429 (Too Many Requests) and includes rate-limit and retry-after
     * headers in the error response.
     *
     * @param ctx the request context to abort
     * @param result the rate-limit result that triggered the rejection
     */
    private static void abortTooManyRequests(ContainerRequestContext ctx, RateLimitResult result) {
        long retryAfter = result.retryAfterSeconds();

        String message;
        if (result.subjectLimitExceeded()) {
            message = "Subject limit exceeded for current billing period";
        } else if (result.monthlyExceeded()) {
            message = "Monthly evaluation quota exceeded";
        } else {
            message = "Request rate limit exceeded";
        }

        Response response =
                Response.status(Response.Status.TOO_MANY_REQUESTS)
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .header(HEADER_LIMIT, String.valueOf(result.limit()))
                        .header(HEADER_REMAINING, "0")
                        .header(HEADER_RESET, String.valueOf(result.resetAtEpochSeconds()))
                        .header(HEADER_RETRY_AFTER, String.valueOf(retryAfter))
                        .entity(
                                Map.of(
                                        "error",
                                        "rate_limit_exceeded",
                                        "message",
                                        message,
                                        "retryAfter",
                                        retryAfter))
                        .build();

        ctx.abortWith(response);
    }

    /**
     * Publishes a {@code rate_limit_exceeded} COUNTER metric for the given tenant and dimension.
     */
    @SuppressWarnings("unchecked")
    private void publishRateLimitMetric(String tenantId, String dimension) {
        try {
            if (metricPublisherInstance.isResolvable()) {
                MetricPublisherService<?> publisher = metricPublisherInstance.get();
                if (publisher != null) {
                    publisher.publish(
                            Metric.builder()
                                    .metricName("rate_limit_exceeded")
                                    .metricValue(1.0)
                                    .metricType(MetricType.COUNTER)
                                    .tenantId(tenantId)
                                    .dimensions(Map.of("dimension", dimension))
                                    .build());
                }
            }
        } catch (Exception e) {
            LOG.debug("Best-effort rate-limit metric publish failed", e);
        }
    }
}
