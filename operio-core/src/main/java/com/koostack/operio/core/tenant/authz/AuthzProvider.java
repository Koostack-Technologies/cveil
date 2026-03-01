package com.koostack.operio.core.tenant.authz;

import com.koostack.operio.core.spi.Plugin;

import java.util.Set;

/**
 * Tenant-scoped authorization provider — manages role assignments
 * for users within a tenant.
 *
 * <p>This SPI is separate from the platform-level
 * {@link com.koostack.operio.core.platform.iam.IdpProvider} which handles
 * authentication, user lifecycle, and tenant provisioning. The
 * {@code AuthzProvider} focuses exclusively on authorization (role
 * management) within an already-provisioned tenant.
 *
 * <h2>Known implementations</h2>
 * <ul>
 *   <li>{@code KeycloakAuthzProvider} (operio-authz-keycloak) — manages
 *       realm-level roles via the Keycloak Admin REST API.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>Implementations must be thread-safe. A single instance is shared
 * across all request threads.
 *
 * @param <T> the plugin configuration type
 */
public interface AuthzProvider<T> extends Plugin<T> {

    /**
     * Assign a role to a user in the tenant's realm.
     *
     * @param tenantId the tenant identifier
     * @param userId   the user's IdP identifier
     * @param role     the role name to assign
     */
    void assignRole(String tenantId, String userId, String role);

    /**
     * Remove a role from a user in the tenant's realm.
     *
     * @param tenantId the tenant identifier
     * @param userId   the user's IdP identifier
     * @param role     the role name to remove
     */
    void removeRole(String tenantId, String userId, String role);

    /**
     * Get all roles assigned to a user in the tenant's realm.
     *
     * @param tenantId the tenant identifier
     * @param userId   the user's IdP identifier
     * @return the user's roles, possibly empty — never {@code null}
     */
    Set<String> getUserRoles(String tenantId, String userId);
}
