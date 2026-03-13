package com.koostack.cveil.core.platform.iam;

import java.util.List;
import java.util.Set;

/**
 * Service provider interface for the Cveil permissions framework.
 *
 * <p>Resolves role assignments and expands them into atomic permissions. Implementations must
 * handle caching (recommended: short TTL, keyed by {@code (userId, tenantId)}).
 *
 * <p>This is separate from {@link IamCommandService} / {@link IamQueryService} because permission
 * resolution is on the hot path of every API request and needs its own caching and optimization
 * strategy.
 */
public interface PermissionService {

    // -- Permission resolution (hot path) -----------------------------------

    /**
     * Resolve the effective permissions for a user in the given tenant.
     *
     * <p>Loads role assignments for {@code (userId, tenantId)} plus any platform-scope assignments
     * ({@code tenant_id IS NULL}), expands via {@code role_permission}, and returns the union of
     * all granted permission IDs.
     *
     * @param userId the Keycloak subject ID
     * @param tenantId the active tenant (personal or org); may be {@code null} for platform scope
     * @return the set of granted permission IDs, never {@code null}
     */
    Set<String> resolvePermissions(String userId, String tenantId);

    /**
     * Resolve the role IDs assigned to a user in the given tenant.
     *
     * @param userId the Keycloak subject ID
     * @param tenantId the active tenant; may be {@code null} for platform scope
     * @return the set of assigned role IDs, never {@code null}
     */
    Set<String> resolveRoles(String userId, String tenantId);

    /**
     * Check whether a user has a specific permission in the given tenant. Default implementation
     * delegates to {@link #resolvePermissions}.
     */
    default boolean hasPermission(String userId, String tenantId, String permission) {
        return resolvePermissions(userId, tenantId).contains(permission);
    }

    // -- Role assignment commands -------------------------------------------

    /**
     * Assign a role to a user in a specific tenant.
     *
     * @param userId the Keycloak subject ID
     * @param tenantId the target tenant (personal or org); {@code null} for platform scope
     * @param roleId the role to assign (must exist in {@code role_definition})
     * @param grantedBy the subject ID of the user granting the role
     * @throws IllegalArgumentException if the role doesn't exist or is not assignable
     * @throws IllegalStateException if the assignment already exists
     */
    void assignRole(String userId, String tenantId, String roleId, String grantedBy);

    /**
     * Remove a role assignment from a user in a specific tenant.
     *
     * @param userId the Keycloak subject ID
     * @param tenantId the target tenant; {@code null} for platform scope
     * @param roleId the role to remove
     */
    void removeRole(String userId, String tenantId, String roleId);

    // -- Role definition queries -------------------------------------------

    /** Metadata for a role definition. */
    record RoleDefinitionInfo(
            String roleId,
            String tenantId,
            String scope,
            String displayName,
            String description,
            boolean isSystem,
            boolean isAssignable,
            List<String> permissions) {}

    /** Metadata for a role assignment. */
    record RoleAssignmentInfo(
            String userId,
            String tenantId,
            String roleId,
            String grantedBy,
            java.time.Instant grantedAt) {}

    /**
     * List all role definitions visible to the given tenant: system roles plus custom roles created
     * for this tenant.
     *
     * @param tenantId the tenant context; system roles are always included
     * @return list of role definitions with their permissions
     */
    List<RoleDefinitionInfo> listRoleDefinitions(String tenantId);

    /**
     * List all role assignments for a user in a specific tenant.
     *
     * @param userId the Keycloak subject ID
     * @param tenantId the target tenant
     * @return list of role assignments
     */
    List<RoleAssignmentInfo> listUserRolesInTenant(String userId, String tenantId);

    /**
     * List all role assignments for all users in a specific tenant.
     *
     * @param tenantId the target tenant
     * @return list of role assignments
     */
    List<RoleAssignmentInfo> listTenantRoleAssignments(String tenantId);

    // -- Custom role management -------------------------------------------

    /**
     * Create a custom role scoped to a tenant.
     *
     * @param tenantId the owning tenant
     * @param roleId unique role identifier (e.g. {@code "my-org-analyst"})
     * @param displayName human-readable name
     * @param description optional description
     * @param permissions the permission IDs to grant
     * @throws IllegalArgumentException if any permission ID is invalid
     */
    void createCustomRole(
            String tenantId,
            String roleId,
            String displayName,
            String description,
            List<String> permissions);

    /**
     * Delete a custom role. System roles cannot be deleted.
     *
     * @param tenantId the owning tenant
     * @param roleId the custom role ID to delete
     * @throws IllegalArgumentException if the role is a system role
     */
    void deleteCustomRole(String tenantId, String roleId);

    // -- Cache management -------------------------------------------------

    /**
     * Invalidate the permission cache for a specific user+tenant pair. Call after role assignment
     * changes.
     */
    void invalidateCache(String userId, String tenantId);

    /** Invalidate all cached permissions. Use sparingly (e.g. after bulk migration). */
    void invalidateAllCaches();
}
