package com.koostack.operio.rest.auth;

import com.koostack.operio.core.models.IdentityContext;

/**
 * Defines the canonical role names used for RBAC enforcement across
 * the Operio REST API.
 *
 * <p>Roles are assigned to API keys at creation time (via the platform
 * admin {@code POST /api/v1/platform/api-keys} endpoint) and are
 * carried in the {@link IdentityContext#roles()}
 * set. The {@link com.koostack.operio.rest.filter.ApiKeyAuthFilter}
 * populates a JAX-RS {@link jakarta.ws.rs.core.SecurityContext} from
 * those roles, enabling programmatic {@code isUserInRole()} checks in
 * resource classes.
 *
 * <h2>Role Hierarchy</h2>
 * <p>There is <strong>no implicit hierarchy</strong> — each role grants
 * exactly the permissions listed below. If an API key needs both read
 * and write access to consent endpoints, it must be assigned both
 * {@link #CONSENT_READ} and {@link #CONSENT_WRITE}. This keeps the
 * authorization model explicit and auditable.
 *
 * <h2>Roles</h2>
 * <table>
 *   <tr><th>Role</th><th>Grants access to</th></tr>
 *   <tr><td>{@code consent:read}</td>
 *       <td>Read-only consent endpoints: evaluate, subject summary,
 *           subject history</td></tr>
 *   <tr><td>{@code consent:write}</td>
 *       <td>Mutating consent endpoints: grant, revoke, grant-batch,
 *           revoke-batch</td></tr>
 *   <tr><td>{@code admin:read}</td>
 *       <td>Read-only admin endpoints: list fields, categories,
 *           entities, purposes, regulations</td></tr>
 *   <tr><td>{@code admin:write}</td>
 *       <td>Mutating admin endpoints: register fields, categories,
 *           purposes, regulations; link fields to categories; link
 *           purposes to regulations</td></tr>
 *   <tr><td>{@code super-admin}</td>
 *       <td>Platform-level endpoints: tenant lifecycle, API key
 *           management. Implicitly has access to all other roles.</td></tr>
 * </table>
 *
 * <h2>Super-admin Override</h2>
 * <p>The {@code super-admin} role is a platform-level privilege that
 * grants unrestricted access to <em>all</em> endpoints, including
 * consent and admin resources. This is enforced by the
 * {@link #requireRole(jakarta.ws.rs.core.SecurityContext, String)}
 * helper, which accepts requests from callers with the
 * {@link #SUPER_ADMIN} role regardless of which specific role was
 * requested.
 *
 * @see com.koostack.operio.rest.filter.ApiKeyAuthFilter
 * @see com.koostack.operio.rest.resource.ConsentResource
 * @see com.koostack.operio.rest.resource.AdminResource
 * @see com.koostack.operio.rest.resource.PlatformResource
 */
public final class Roles {

    // -- Consent roles --------------------------------------------------

    /**
     * Grants access to read-only consent operations: evaluate consent,
     * retrieve subject summary, and retrieve subject history.
     */
    public static final String CONSENT_READ = "consent:read";

    /**
     * Grants access to mutating consent operations: grant, revoke,
     * grant-batch, and revoke-batch.
     */
    public static final String CONSENT_WRITE = "consent:write";

    // -- Admin roles ----------------------------------------------------

    /**
     * Grants access to read-only administration operations: listing
     * fields, categories, entities, purposes, and regulations.
     */
    public static final String ADMIN_READ = "admin:read";

    /**
     * Grants access to mutating administration operations: registering
     * fields, categories, purposes, and regulations, and linking fields
     * to categories or purposes to regulations.
     */
    public static final String ADMIN_WRITE = "admin:write";

    // -- Platform roles -------------------------------------------------

    /**
     * Platform-level super-administrator role. Grants access to tenant
     * lifecycle management and API key administration, as well as all
     * consent and admin endpoints (super-admin override).
     */
    public static final String SUPER_ADMIN = "super-admin";

    // -- Enforcement helpers --------------------------------------------

    /**
     * Verifies that the current caller has the specified role, or is a
     * {@link #SUPER_ADMIN} (which bypasses all role checks).
     *
     * <p>If the caller lacks the required role, an
     * {@link com.koostack.operio.core.exception.OperioAuthException}
     * is thrown, which the
     * {@link com.koostack.operio.rest.resource.OperioExceptionMapper}
     * maps to HTTP 403 Forbidden.
     *
     * @param securityContext the JAX-RS security context populated by
     *        {@code ApiKeyAuthFilter}; may be {@code null} for
     *        unauthenticated requests
     * @param requiredRole the role that must be present (one of the
     *        constants defined in this class)
     * @throws com.koostack.operio.core.exception.OperioAuthException
     *         if the caller does not have the required role
     */
    public static void requireRole(
        jakarta.ws.rs.core.SecurityContext securityContext,
        String requiredRole
    ) {
        if (securityContext != null
                && securityContext.isUserInRole(SUPER_ADMIN)) {
            // Super-admin bypasses all role checks
            return;
        }
        if (securityContext == null
                || !securityContext.isUserInRole(requiredRole)) {
            throw new com.koostack.operio.core.exception.OperioAuthException(
                "Access denied: this endpoint requires the '"
                    + requiredRole
                    + "' role"
            );
        }
    }

    /**
     * Verifies that the current caller has <em>any</em> of the
     * specified roles, or is a {@link #SUPER_ADMIN}.
     *
     * <p>Useful for endpoints that accept multiple roles (e.g. an
     * endpoint accessible to both readers and writers).
     *
     * @param securityContext the JAX-RS security context
     * @param roles the roles, any one of which satisfies the check
     * @throws com.koostack.operio.core.exception.OperioAuthException
     *         if the caller does not have any of the required roles
     */
    public static void requireAnyRole(
        jakarta.ws.rs.core.SecurityContext securityContext,
        String... roles
    ) {
        if (securityContext != null
                && securityContext.isUserInRole(SUPER_ADMIN)) {
            return;
        }
        if (securityContext != null) {
            for (String role : roles) {
                if (securityContext.isUserInRole(role)) {
                    return;
                }
            }
        }
        throw new com.koostack.operio.core.exception.OperioAuthException(
            "Access denied: this endpoint requires one of ["
                + String.join(", ", roles)
                + "]"
        );
    }

    private Roles() {
        // Utility class — prevent instantiation
    }
}
