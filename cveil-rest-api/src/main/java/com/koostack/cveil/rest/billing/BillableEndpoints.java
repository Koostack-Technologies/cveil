package com.koostack.cveil.rest.billing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Defines which API path prefixes constitute the public, billable API surface.
 *
 * <p>Only requests to these paths are:
 *
 * <ul>
 *   <li>Shown in the public tenant-facing API documentation
 *   <li>Accessible via Personal Access Tokens (PATs)
 *   <li>Eligible for usage metering (when authenticated via PAT)
 * </ul>
 *
 * <p>All other endpoints (billing, IAM, metrics, platform, health, webhooks) are internal/dashboard
 * endpoints accessible only via JWT session authentication.
 *
 * @see com.koostack.cveil.rest.filter.ApiKeyAuthFilter
 * @see com.koostack.cveil.rest.filter.BillableMetricFilter
 * @see com.koostack.cveil.rest.docs.OpenApiSplitService
 */
public final class BillableEndpoints {

    private BillableEndpoints() {
        // Utility class — no instances
    }

    /**
     * Ordered list of billable path prefixes and their resource labels. The label is used as the
     * {@code resource} dimension in the {@code api_billable_request} metric.
     */
    private static final List<Map.Entry<String, String>> BILLABLE_PREFIXES =
            List.of(
                    Map.entry("/api/v1/subjects", "consent"),
                    Map.entry("/api/v1/governance", "governance"),
                    Map.entry("/api/v1/catalogue", "catalogue"),
                    Map.entry("/api/v1/consent", "graph"),
                    Map.entry("/api/v1/dsar", "dsar"));

    /** Auth provider value that identifies Personal Access Tokens. */
    public static final String PAT_PROVIDER = "access-token";

    /**
     * Returns {@code true} if the given request path falls under a billable resource.
     *
     * @param path the request URI path (e.g. {@code /api/v1/subjects/consent/grant})
     * @return true if the path is within the public billable API surface
     */
    public static boolean isBillable(String path) {
        if (path == null) {
            return false;
        }
        for (var entry : BILLABLE_PREFIXES) {
            String prefix = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the billable resource label for a given path.
     *
     * @param path the request URI path
     * @return the resource label (e.g. "consent", "governance") or empty if not billable
     */
    public static Optional<String> resolveResource(String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (var entry : BILLABLE_PREFIXES) {
            String prefix = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} if the identity provider indicates a Personal Access Token.
     *
     * @param provider the {@code IdentityContext.provider()} value
     * @return true for PAT-authenticated requests
     */
    public static boolean isPatAuth(String provider) {
        return PAT_PROVIDER.equals(provider);
    }

    /**
     * Returns the list of billable path prefixes. Used by the OpenAPI split service to filter which
     * paths appear in the public tenant-facing documentation.
     *
     * @return unmodifiable list of path prefix strings
     */
    public static List<String> billablePathPrefixes() {
        return BILLABLE_PREFIXES.stream().map(Map.Entry::getKey).toList();
    }
}
