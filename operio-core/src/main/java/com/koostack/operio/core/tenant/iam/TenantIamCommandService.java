package com.koostack.operio.core.tenant.iam;

import com.koostack.operio.core.spi.Plugin;
import java.util.Set;

/**
 * Tenant-scoped IAM command service — registers users, manages service accounts, and assigns roles
 * within a single tenant's Keycloak realm.
 *
 * <p>Each command publishes a domain event to the tenant event store ({@code t_{tenantId}_event}).
 * The tenant IAM projection handler translates these events into Keycloak Admin API calls.
 *
 * <p>All operations are implicitly scoped to the current tenant via the realm ID derived from
 * {@link com.koostack.operio.core.models.TenantContext}.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface TenantIamCommandService<T> extends Plugin<T> {

    /** Result of registering a tenant user. */
    record RegisterUserResult(String userId, String email, String realmId) {}

    /** Result of creating a tenant service account. */
    record CreateServiceAccountResult(String clientId, String clientSecret, String realmId) {}

    RegisterUserResult registerUser(
            String tenantId, String email, String fullName, Set<String> roles,
            boolean temporaryPassword);

    void suspendUser(String tenantId, String userId);

    void activateUser(String tenantId, String userId);

    CreateServiceAccountResult createServiceAccount(
            String tenantId, String name, Set<String> roles);

    void revokeServiceAccount(String tenantId, String clientId);

    void assignRole(String tenantId, String userId, String role);

    void removeRole(String tenantId, String userId, String role);
}
