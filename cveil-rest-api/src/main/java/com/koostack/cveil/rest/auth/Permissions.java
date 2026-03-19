package com.koostack.cveil.rest.auth;

import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.AuthorizationContext;

/**
 * Permission-based authorization enforcement for REST resource methods.
 *
 * <p>Replaces the role-based checks in {@link Roles} with atomic permission checks backed by the
 * Cveil permissions framework. The {@link AuthorizationContext} is resolved per-request by the auth
 * filter from the {@code role_assignment} / {@code role_permission} tables.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @GET
 * public Response listSubjects(@Context SecurityContext sc) {
 *     AuthorizationContext authz = CveilRequestHolder.getAuthorizationContext(sc);
 *     Permissions.require(authz, "consent:read");
 *     // ...
 * }
 * }</pre>
 *
 * <h2>Super-admin Bypass</h2>
 *
 * <p>Users with the {@code super-admin} role bypass all permission checks — their {@link
 * AuthorizationContext#isSuperAdmin()} returns {@code true}.
 *
 * @see AuthorizationContext
 * @see Roles
 */
public final class Permissions {

    // -- Consent permissions ------------------------------------------------

    public static final String CONSENT_GRANT = "consent:grant";
    public static final String CONSENT_REVOKE = "consent:revoke";
    public static final String CONSENT_EVALUATE = "consent:evaluate";
    public static final String CONSENT_READ = "consent:read";
    public static final String CONSENT_EXPORT = "consent:export";
    public static final String CONSENT_DSAR = "consent:dsar";
    public static final String CONSENT_GRAPH_READ = "consent:graph:read";

    // -- Catalogue permissions ----------------------------------------------

    public static final String CATALOGUE_READ = "catalogue:read";
    public static final String CATALOGUE_WRITE = "catalogue:write";

    // -- Governance permissions ---------------------------------------------

    public static final String GOVERNANCE_READ = "governance:read";
    public static final String GOVERNANCE_WRITE = "governance:write";

    // -- Events permissions -------------------------------------------------

    public static final String EVENTS_READ = "events:read";

    // -- Reports permissions ------------------------------------------------

    public static final String REPORTS_READ = "reports:read";

    // -- Organization permissions -------------------------------------------

    public static final String ORG_READ = "org:read";
    public static final String ORG_WRITE = "org:write";
    public static final String ORG_MEMBER_MANAGE = "org:member:manage";
    public static final String ORG_ROLE_MANAGE = "org:role:manage";

    // -- IAM permissions ----------------------------------------------------

    public static final String IAM_USER_READ = "iam:user:read";
    public static final String IAM_USER_MANAGE = "iam:user:manage";

    // -- Platform permissions -----------------------------------------------

    public static final String PLATFORM_TENANT_READ = "platform:tenant:read";
    public static final String PLATFORM_TENANT_MANAGE = "platform:tenant:manage";

    // -- Enforcement helpers ------------------------------------------------

    /**
     * Verify the caller has the specified permission, or is a super-admin.
     *
     * @param authz the authorization context for the current request
     * @param permission the required permission (e.g. {@code "consent:grant"})
     * @throws CveilAuthException if the caller lacks the permission (HTTP 403)
     */
    public static void require(AuthorizationContext authz, String permission) {
        if (authz != null && authz.isSuperAdmin()) {
            return;
        }
        if (authz == null || !authz.hasPermission(permission)) {
            throw new CveilAuthException(
                    "Access denied: this endpoint requires the '" + permission + "' permission");
        }
    }

    /**
     * Verify the caller has <em>any</em> of the specified permissions, or is a super-admin.
     *
     * @param authz the authorization context
     * @param permissions the permissions, any one of which satisfies the check
     * @throws CveilAuthException if the caller lacks all of the permissions (HTTP 403)
     */
    public static void requireAny(AuthorizationContext authz, String... permissions) {
        if (authz != null && authz.isSuperAdmin()) {
            return;
        }
        if (authz != null) {
            for (String p : permissions) {
                if (authz.hasPermission(p)) {
                    return;
                }
            }
        }
        throw new CveilAuthException(
                "Access denied: this endpoint requires one of ["
                        + String.join(", ", permissions)
                        + "]");
    }

    /**
     * Verify the caller has <em>all</em> of the specified permissions, or is a super-admin.
     *
     * @param authz the authorization context
     * @param permissions all of these must be present
     * @throws CveilAuthException if the caller lacks any of the permissions (HTTP 403)
     */
    public static void requireAll(AuthorizationContext authz, String... permissions) {
        if (authz != null && authz.isSuperAdmin()) {
            return;
        }
        if (authz == null) {
            throw new CveilAuthException(
                    "Access denied: this endpoint requires all of ["
                            + String.join(", ", permissions)
                            + "]");
        }
        for (String p : permissions) {
            if (!authz.hasPermission(p)) {
                throw new CveilAuthException(
                        "Access denied: missing required permission '" + p + "'");
            }
        }
    }

    private Permissions() {
        // Utility class — prevent instantiation
    }
}
