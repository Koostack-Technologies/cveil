package com.koostack.cveil.rest.filter;

import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.gate.FeatureGateChecker;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS filter that enforces plan-based feature gating on authenticated requests.
 *
 * <p>Runs at {@link Priorities#AUTHORIZATION} + 1 (2001), immediately after the {@link
 * RateLimitFilter}. For each request, it resolves the tenant's plan and checks whether the
 * requested feature is enabled via {@link FeatureGateChecker}.
 *
 * <h2>Feature Key Mapping</h2>
 *
 * <p>Endpoint path prefixes are mapped to feature keys in {@code cveilcore.plan_feature}:
 *
 * <ul>
 *   <li>{@code /dsar} → {@code feature.dsar}
 *   <li>{@code /reports} → {@code feature.reports}
 *   <li>{@code /webhooks} or {@code /webhook-management} → {@code feature.webhooks}
 *   <li>{@code /analytics} or {@code /metrics} → {@code feature.analytics}
 *   <li>{@code /billing} → {@code feature.billing}
 * </ul>
 *
 * <p>Endpoints not matching any feature key are allowed through (no feature gate). Public
 * endpoints, super-admin requests, and core platform endpoints (IAM, consent) are never gated.
 *
 * @see FeatureGateChecker
 * @see RateLimitFilter
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 1)
public class FeatureGateFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(FeatureGateFilter.class);

    private static final String PUBLIC_PREFIX = "/api/v1/public/";

    /**
     * Maps path segments to feature keys. Checked in order — first match wins. The path is
     * normalized to start with "/" and matched with {@code contains()} for flexibility.
     */
    private static final Map<String, String> PATH_TO_FEATURE =
            Map.of(
                    "/dsar", "feature.dsar",
                    "/reports", "feature.reports",
                    "/webhook", "feature.webhooks",
                    "/analytics", "feature.analytics",
                    "/metrics", "feature.analytics",
                    "/billing", "feature.billing");

    @Inject CveilRequestHolder requestHolder;

    @Inject jakarta.enterprise.inject.Instance<FeatureGateChecker> featureGateInstance;

    @Inject
    @SuppressWarnings("rawtypes")
    jakarta.enterprise.inject.Instance<com.koostack.cveil.core.platform.core.CoreQueryService>
            coreQueryInstance;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        // Skip if feature gate checker is not available
        if (!featureGateInstance.isResolvable()) {
            return;
        }

        // Skip unauthenticated (public) requests
        if (!requestHolder.isAuthenticated()) {
            return;
        }

        var identity = requestHolder.identity();
        String tenantId = identity.tenantId();

        // Skip super-admin (no tenant, platform-level)
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }

        // Determine which feature key this path requires
        String path = ctx.getUriInfo().getPath();
        String normalized = path.startsWith("/") ? path : "/" + path;

        // Skip public endpoints
        if (normalized.startsWith(PUBLIC_PREFIX)) {
            return;
        }

        // Skip dashboard HTML pages (feature gates apply to API endpoints only)
        if (normalized.startsWith("/dashboard")) {
            return;
        }

        String featureKey = resolveFeatureKey(normalized);
        if (featureKey == null) {
            // No feature gate applies to this endpoint
            return;
        }

        // Resolve the tenant's plan
        String planId = resolvePlanId(tenantId);
        if (planId == null) {
            // No plan assigned — allow through (free tier has no restrictions)
            return;
        }

        FeatureGateChecker checker = featureGateInstance.get();
        if (!checker.isFeatureEnabled(planId, featureKey)) {
            LOG.info(
                    "FeatureGateFilter: blocked {} for tenant '{}' — "
                            + "feature '{}' disabled on plan '{}'",
                    normalized,
                    tenantId,
                    featureKey,
                    planId);

            ctx.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                            .type(MediaType.APPLICATION_JSON_TYPE)
                            .entity(
                                    Map.of(
                                            "error",
                                            "feature_disabled",
                                            "message",
                                            "This feature is not available on your current plan."
                                                + " Please upgrade to access this functionality.",
                                            "feature",
                                            featureKey))
                            .build());
        }
    }

    private static String resolveFeatureKey(String path) {
        for (var entry : PATH_TO_FEATURE.entrySet()) {
            if (path.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String resolvePlanId(String tenantId) {
        try {
            if (!coreQueryInstance.isResolvable()) {
                return null;
            }
            com.koostack.cveil.core.platform.core.CoreQueryService coreQuery =
                    coreQueryInstance.get();
            if (coreQuery == null) {
                return null;
            }
            var tenant = coreQuery.getTenant(tenantId);
            if (tenant == null) {
                return null;
            }
            String planId = tenant.plan();
            return (planId != null && !planId.isBlank()) ? planId : null;
        } catch (Exception e) {
            LOG.warn(
                    "FeatureGateFilter: failed to resolve plan for tenant '{}': {}",
                    tenantId,
                    e.getMessage());
            return null; // fail-open
        }
    }
}
