package com.koostack.operio.core.tenant.iam;

import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.spi.Plugin;
import java.util.List;
import java.util.Set;

/**
 * Tenant-scoped IAM query service — reads users, service accounts, and roles from the tenant's
 * Keycloak realm. Also provides authentication and token verification.
 *
 * @deprecated Use {@link com.koostack.operio.core.platform.iam.IamQueryService} with the single
 *     realm and Keycloak Organizations. Tenant-scoped queries are now handled by the unified IAM
 *     query service with organization-scoped filtering. Will be removed in the module cleanup
 *     phase.
 * @param <T> the configuration type for the backing implementation
 */
@Deprecated(forRemoval = true)
public interface TenantIamQueryService<T> extends Plugin<T> {

    /** Projected service account metadata for a tenant. */
    record ServiceAccountInfo(
            String clientId,
            String name,
            String realmId,
            Set<String> roles,
            String status,
            java.time.Instant createdAt) {}

    // -- User queries ---------------------------------------------------------

    List<IdpUser> listUsers(String tenantId);

    IdpUser getUser(String tenantId, String userId);

    IdpUser findUserByEmail(String tenantId, String email);

    IdpUser findUserByUsername(String tenantId, String username);

    Set<String> getUserRoles(String tenantId, String userId);

    Set<String> getUserRolesByEmail(String tenantId, String email);

    Set<String> getUserRolesByUsername(String tenantId, String username);

    String resolveUserId(String tenantId, String emailOrUsername);

    // -- Service account queries ----------------------------------------------

    List<ServiceAccountInfo> listServiceAccounts(String tenantId);

    ServiceAccountInfo getServiceAccount(String tenantId, String clientId);

    // -- Authentication -------------------------------------------------------

    AuthResult authenticate(String tenantId, String email, String password);

    AuthResult refreshToken(String tenantId, String refreshToken);

    IdentityContext verifyToken(String tenantId, String accessToken);

    void logout(String tenantId, String refreshToken);
}
