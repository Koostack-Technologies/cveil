package com.koostack.operio.core.tenant.iam;

import com.koostack.operio.core.spi.Plugin;
import java.util.Set;

/**
 * Tenant-scoped IAM command service — registers users, manages service accounts, and assigns roles
 * within a single tenant's Keycloak realm.
 *
 * @deprecated Use {@link com.koostack.operio.core.platform.iam.IamCommandService} with the single
 *     realm and Keycloak Organizations. Tenant-scoped operations are now handled by the unified IAM
 *     service with organization membership. Will be removed in the module cleanup phase.
 * @param <T> the configuration type for the backing implementation
 */
@Deprecated(forRemoval = true)
public interface TenantIamCommandService<T> extends Plugin<T> {

    /** Result of registering a tenant user. */
    record RegisterUserResult(String username, String email, String realmId) {}

    /** Result of creating a tenant service account. */
    record CreateServiceAccountResult(String clientId, String clientSecret, String realmId) {}

    RegisterUserResult registerUser(
            String tenantId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword);

    void suspendUser(String tenantId, String emailOrUsername);

    void activateUser(String tenantId, String emailOrUsername);

    CreateServiceAccountResult createServiceAccount(
            String tenantId, String name, Set<String> roles);

    void revokeServiceAccount(String tenantId, String clientId);

    void assignRole(String tenantId, String emailOrUsername, String role);

    void removeRole(String tenantId, String emailOrUsername, String role);
}
