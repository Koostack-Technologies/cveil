package com.koostack.operio.core.models;

import java.util.Set;

/**
 * Resolved authorization state for the current request.
 *
 * <p>Separates <em>authorization</em> (what you can do) from <em>authentication</em> ({@link
 * IdentityContext} — who you are). Built after authentication by resolving the user's role
 * assignments in the active tenant and expanding them into atomic permissions.
 *
 * @param userId Keycloak subject ID of the authenticated user
 * @param tenantId the active tenant for this request (personal or org)
 * @param roles role IDs assigned to this user in the active tenant
 * @param permissions expanded atomic permissions (e.g. {@code "consent:grant"}, {@code
 *     "catalogue:read"})
 */
public record AuthorizationContext(
        String userId, String tenantId, Set<String> roles, Set<String> permissions) {

    /**
     * Check whether this context includes the given permission.
     *
     * @param permission the permission to check (e.g. {@code "consent:grant"})
     * @return {@code true} if granted
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /** Check whether this context includes <em>any</em> of the given permissions. */
    public boolean hasAnyPermission(String... perms) {
        for (String p : perms) {
            if (permissions.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** Check whether this context includes the given role. */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** Returns {@code true} if this is a platform-scoped super-admin context. */
    public boolean isSuperAdmin() {
        return roles.contains("super-admin");
    }

    /** An empty authorization context for unauthenticated or system-internal requests. */
    public static AuthorizationContext none() {
        return new AuthorizationContext(null, null, Set.of(), Set.of());
    }
}
