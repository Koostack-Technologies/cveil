package com.koostack.cveil.rest.filter;

import com.koostack.cveil.rest.billing.BillableEndpoints;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.util.MetricsHelper;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS response filter that publishes a {@code api_billable_request} metric for every
 * PAT-authenticated request to a billable API endpoint.
 *
 * <p><strong>Billing rule:</strong> A request is billable if and only if:
 *
 * <ol>
 *   <li>The request path falls under a billable resource (governance, consent/subjects, catalogue,
 *       graph, events)
 *   <li>The request was authenticated via a Personal Access Token (PAT)
 * </ol>
 *
 * <p>Dashboard/JWT requests are <em>never</em> metered, even for write operations.
 *
 * <p>The metric is published with three dimensions:
 *
 * <ul>
 *   <li>{@code resource} — the billable resource group (e.g. "consent", "governance")
 *   <li>{@code method} — the HTTP method (GET, POST, PUT, DELETE)
 *   <li>{@code auth_type} — always "pat" (only PAT requests are metered)
 * </ul>
 *
 * <p>Runs at priority 3500, after rate limiting (2000) and authorization, to ensure the request has
 * been fully authenticated and authorized before metering.
 *
 * @see BillableEndpoints
 * @see MetricsHelper
 */
@Provider
@Priority(3500)
public class BillableMetricFilter implements ContainerResponseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BillableMetricFilter.class);

    /** The metric name published for billable API requests. */
    public static final String METRIC_NAME = "api_billable_request";

    @Inject CveilRequestHolder requestHolder;
    @Inject MetricsHelper metrics;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response)
            throws IOException {
        // Skip unauthenticated requests
        if (!requestHolder.isAuthenticated() || requestHolder.identity() == null) {
            return;
        }

        // Only meter PAT-authenticated requests
        String provider = requestHolder.identity().provider();
        if (!BillableEndpoints.isPatAuth(provider)) {
            return;
        }

        // Resolve request path and check if it's a billable endpoint
        String path = request.getUriInfo().getPath();
        if (path != null && !path.startsWith("/")) {
            path = "/" + path;
        }

        Optional<String> resource = BillableEndpoints.resolveResource(path);
        if (resource.isEmpty()) {
            return;
        }

        // Only meter successful responses — client errors (4xx) and server
        // errors (5xx) should not count as billable API usage.
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            return;
        }

        // Publish the billable request metric
        String tenantId = requestHolder.identity().tenantId();
        String method = request.getMethod();

        metrics.counter(
                METRIC_NAME,
                tenantId,
                Map.of("resource", resource.get(), "method", method, "auth_type", "pat"));

        LOG.trace(
                "Billable request metered: tenant={}, resource={}, method={}",
                tenantId,
                resource.get(),
                method);
    }
}
