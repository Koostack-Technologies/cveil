package com.koostack.cveil.rest.filter;

import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * JAX-RS filter that manages distributed tracing context for every HTTP request processed by the
 * Cveil REST API.
 *
 * <p>This filter implements both {@link ContainerRequestFilter} and {@link ContainerResponseFilter}
 * to bracket the entire request lifecycle:
 *
 * <h2>Request Phase (pre-authentication)</h2>
 *
 * <ol>
 *   <li>Reads the {@code X-Request-Id} header from the incoming request. If absent, generates a
 *       random UUID.
 *   <li>Stores the request ID as a JAX-RS request property so downstream filters and resource
 *       methods can access it.
 *   <li>Sets the {@code requestId} key in the SLF4J {@link MDC} so all log statements emitted
 *       during request processing include the request ID automatically (when the log format
 *       includes {@code %X{requestId}}).
 * </ol>
 *
 * <h2>Response Phase (post-resource-method)</h2>
 *
 * <ol>
 *   <li>Reads the authenticated {@link IdentityContext} from the {@link CveilRequestHolder} and
 *       enriches the MDC with {@code tenantId} and {@code subject} — these values are only
 *       available after the auth filter has run, so they are set here (on the response side) to
 *       ensure final response-phase log lines include the full tracing context.
 *   <li>Echoes the {@code X-Request-Id} header back in the HTTP response so clients can correlate
 *       requests with server-side logs.
 *   <li>Clears the SLF4J MDC to prevent context leaking across requests (defense-in-depth — with
 *       virtual threads each request has its own thread, but clearing is still good hygiene).
 * </ol>
 *
 * <h2>MDC Keys</h2>
 *
 * <table>
 *   <tr><th>Key</th><th>Set When</th><th>Description</th></tr>
 *   <tr><td>{@code requestId}</td><td>Request phase</td>
 *       <td>UUID from {@code X-Request-Id} header or auto-generated</td></tr>
 *   <tr><td>{@code tenantId}</td><td>Response phase (also set by
 *       {@code ApiKeyAuthFilter} in request phase after auth)</td>
 *       <td>Authenticated tenant ID, or {@code -} if absent</td></tr>
 *   <tr><td>{@code subject}</td><td>Response phase (also set by
 *       {@code ApiKeyAuthFilter} in request phase after auth)</td>
 *       <td>Authenticated subject (user/key identity), or {@code -} if
 *       absent</td></tr>
 * </table>
 *
 * <h2>Priority &amp; Ordering</h2>
 *
 * <p>Priority is {@code Priorities.AUTHENTICATION - 100} (= 900), so:
 *
 * <ul>
 *   <li><strong>Request phase:</strong> runs <em>before</em> the auth filter ({@code
 *       AUTHENTICATION} = 1000) — the request ID is available in MDC for auth-related log messages.
 *   <li><strong>Response phase:</strong> JAX-RS response filters execute in reverse priority order
 *       (highest priority first), so this filter runs <em>last</em> among response filters — MDC is
 *       cleared only after all other response filters have had a chance to log.
 * </ul>
 *
 * <h2>Integration with {@code ApiKeyAuthFilter}</h2>
 *
 * <p>The auth filter enriches MDC with {@code tenantId} and {@code subject} immediately after
 * successful authentication (request phase). This means log lines emitted during resource method
 * execution already include the full tracing context. The response-phase enrichment here is a
 * safety net that ensures the values are present even if the auth filter didn't set them (e.g. on
 * public endpoints or auth failures).
 *
 * @see ApiKeyAuthFilter
 * @see CveilRequestHolder
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestTracingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestTracingFilter.class);

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** HTTP header for request correlation. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * JAX-RS request property key under which the request ID is stored. Downstream filters and
     * resource methods can read this property to access the current request ID without going
     * through MDC.
     */
    public static final String REQUEST_ID_PROPERTY = "cveil.request-id";

    /** JAX-RS request property key for the request start time (nanos). */
    private static final String START_TIME_PROPERTY = "cveil.request-start-nanos";

    /** MDC key for the request correlation ID. */
    public static final String MDC_REQUEST_ID = "requestId";

    /** MDC key for the authenticated tenant ID. */
    public static final String MDC_TENANT_ID = "tenantId";

    /** MDC key for the authenticated subject (user/key identity). */
    public static final String MDC_SUBJECT = "subject";

    /** Placeholder value used in MDC when a field is not available. */
    private static final String MDC_ABSENT = "-";

    // -----------------------------------------------------------------------
    // Injected dependencies
    // -----------------------------------------------------------------------

    /**
     * Request-scoped holder populated by {@link ApiKeyAuthFilter} with the authenticated identity.
     * Used in the response phase to enrich MDC with {@code tenantId} and {@code subject}.
     */
    @Inject CveilRequestHolder requestHolder;

    // -----------------------------------------------------------------------
    // Request filter — runs BEFORE authentication
    // -----------------------------------------------------------------------

    /**
     * Extracts or generates the request ID and initializes the SLF4J MDC.
     *
     * <p>This runs at priority {@code AUTHENTICATION - 100}, so the request ID is available in MDC
     * before the auth filter logs anything.
     *
     * @param requestCtx the incoming request context
     * @throws IOException never thrown — declared by the interface contract
     */
    @Override
    public void filter(ContainerRequestContext requestCtx) throws IOException {
        // 1. Read or generate the request ID
        String requestId = requestCtx.getHeaderString(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            LOG.trace("No {} header present — generated: {}", REQUEST_ID_HEADER, requestId);
        } else {
            requestId = requestId.trim();
            LOG.trace("Using client-provided {}: {}", REQUEST_ID_HEADER, requestId);
        }

        // 2. Store as request property for downstream access
        requestCtx.setProperty(REQUEST_ID_PROPERTY, requestId);

        // 3. Record start time for duration tracking
        requestCtx.setProperty(START_TIME_PROPERTY, System.nanoTime());

        // 4. Set MDC for structured logging
        MDC.put(MDC_REQUEST_ID, requestId);

        // tenantId and subject are not yet available (auth hasn't run).
        // Set placeholder values so log format doesn't show blanks.
        MDC.put(MDC_TENANT_ID, MDC_ABSENT);
        MDC.put(MDC_SUBJECT, MDC_ABSENT);

        // 5. INFO-level request start log (method + path only)
        String method = requestCtx.getMethod();
        String path = requestCtx.getUriInfo().getAbsolutePath().getPath();
        LOG.info("→ {} {}", method, path);
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "→ {} {} (full URI: {})",
                    method,
                    path,
                    requestCtx.getUriInfo().getRequestUri());
        }
    }

    // -----------------------------------------------------------------------
    // Response filter — runs AFTER all other response filters
    // -----------------------------------------------------------------------

    /**
     * Echoes the request ID in the response header, enriches MDC with post-authentication context,
     * and clears MDC.
     *
     * <p>Because this filter has the lowest priority among response filters (JAX-RS response
     * filters execute in descending priority order), it runs last — ensuring the MDC is cleared
     * only after all other response filters have completed.
     *
     * @param requestCtx the request context
     * @param responseCtx the response context
     * @throws IOException never thrown — declared by the interface contract
     */
    @Override
    public void filter(ContainerRequestContext requestCtx, ContainerResponseContext responseCtx)
            throws IOException {
        try {
            // 1. Retrieve the request ID (stored in request phase)
            Object requestIdObj = requestCtx.getProperty(REQUEST_ID_PROPERTY);
            String requestId = requestIdObj instanceof String s ? s : MDC.get(MDC_REQUEST_ID);

            // 2. Echo X-Request-Id in the response for client correlation
            if (requestId != null) {
                responseCtx.getHeaders().putSingle(REQUEST_ID_HEADER, requestId);
            }

            // 3. Enrich MDC with post-authentication identity context
            //    (safety net — ApiKeyAuthFilter should have set these
            //    during request processing, but we ensure they're present
            //    for any final response-phase log lines)
            enrichMdcWithIdentity();

            // 4. Compute request duration
            String method = requestCtx.getMethod();
            String path = requestCtx.getUriInfo().getAbsolutePath().getPath();
            int status = responseCtx.getStatus();
            long durationMs = -1;
            Object startObj = requestCtx.getProperty(START_TIME_PROPERTY);
            if (startObj instanceof Long startNanos) {
                durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            }

            // 5. INFO-level request completion log (method + path + status + duration)
            LOG.info("← {} {} {} {}ms", method, path, status, durationMs);
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "← {} {} {} {}ms (full URI: {}, requestId={}, tenant={}, subject={})",
                        method,
                        path,
                        status,
                        durationMs,
                        requestCtx.getUriInfo().getRequestUri(),
                        requestId != null ? requestId : MDC_ABSENT,
                        MDC.get(MDC_TENANT_ID),
                        MDC.get(MDC_SUBJECT));
            }
        } finally {
            // Clear MDC to prevent context leaking.
            //    With virtual threads each request has its own thread, but
            //    clearing is still good hygiene and protects against any
            //    thread-reuse edge cases.
            MDC.clear();
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Enriches the SLF4J MDC with {@code tenantId} and {@code subject} from the authenticated
     * identity stored in the {@link CveilRequestHolder}.
     *
     * <p>If no identity is available (unauthenticated request, public endpoint, or auth failure),
     * placeholder values ({@code "-"}) are left in place.
     */
    private void enrichMdcWithIdentity() {
        if (requestHolder == null || !requestHolder.isAuthenticated()) {
            return;
        }

        IdentityContext identity = requestHolder.identity();
        if (identity == null) {
            return;
        }

        String tenantId = identity.tenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            MDC.put(MDC_TENANT_ID, tenantId);
        }

        String subject = identity.subject();
        if (subject != null && !subject.isBlank()) {
            MDC.put(MDC_SUBJECT, subject);
        }
    }
}
