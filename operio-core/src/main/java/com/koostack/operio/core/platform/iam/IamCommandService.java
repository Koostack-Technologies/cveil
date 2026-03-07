package com.koostack.operio.core.platform.iam;

import com.koostack.operio.core.spi.Plugin;
import java.util.Set;

/**
 * Command service for IAM operations — registers users, manages service accounts, and assigns
 * roles.
 *
 * <p>Each command publishes a domain event to the event store. The projection handler translates
 * these events into Keycloak Admin API calls.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface IamCommandService<T> extends Plugin<T> {

    /**
     * Result of registering a user.
     *
     * @param userId the Keycloak user ID
     * @param email the user's email
     * @param realmId the realm the user was registered in
     */
    record RegisterUserResult(String userId, String email, String realmId) {}

    /**
     * Result of creating a service account (Keycloak confidential client).
     *
     * @param clientId the Keycloak client ID
     * @param clientSecret the generated client secret (returned once)
     * @param realmId the realm the service account was created in
     */
    record CreateServiceAccountResult(String clientId, String clientSecret, String realmId) {}

    /**
     * Register a new user in the given realm. Publishes a {@code UserRegisteredEvent}.
     *
     * @param realmId the Keycloak realm (tenantId or platform realm)
     * @param email the user's email address
     * @param fullName the user's display name
     * @param roles initial roles to assign
     * @param temporaryPassword if true, user must change password on first login
     * @return the registration result
     */
    RegisterUserResult registerUser(
            String realmId, String email, String fullName, Set<String> roles,
            boolean temporaryPassword);

    /**
     * Suspend a user account. Publishes a {@code UserSuspendedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param userId the user's Keycloak ID
     */
    void suspendUser(String realmId, String userId);

    /**
     * Re-activate a suspended user account. Publishes a {@code UserActivatedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param userId the user's Keycloak ID
     */
    void activateUser(String realmId, String userId);

    /**
     * Create a service account (confidential client with client credentials grant). Publishes a
     * {@code ServiceAccountCreatedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param name human-readable name for the service account
     * @param roles roles to assign to the service account
     * @return the result containing the one-time client secret
     */
    CreateServiceAccountResult createServiceAccount(
            String realmId, String name, Set<String> roles);

    /**
     * Revoke/disable a service account. Publishes a {@code ServiceAccountRevokedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param clientId the Keycloak client ID
     */
    void revokeServiceAccount(String realmId, String clientId);

    /**
     * Assign a role to a user. Publishes a {@code RoleAssignedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param userId the user's Keycloak ID
     * @param role the role name to assign
     */
    void assignRole(String realmId, String userId, String role);

    /**
     * Remove a role from a user. Publishes a {@code RoleRemovedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param userId the user's Keycloak ID
     * @param role the role name to remove
     */
    void removeRole(String realmId, String userId, String role);

    // -- Legacy API key methods (kept for backward compatibility) --------------

    /**
     * @deprecated Use {@link #createServiceAccount} for new code.
     */
    @Deprecated(forRemoval = true)
    record CreateApiTokenResult(String keyId, String keyPrefix, String plaintextKey) {}

    /**
     * @deprecated Use {@link #createServiceAccount} for new code.
     */
    @Deprecated(forRemoval = true)
    default CreateApiTokenResult createApiToken(
            String tenantId, String name, String environment, String roles) {
        throw new UnsupportedOperationException(
                "API key generation is deprecated — use createServiceAccount()");
    }

    /**
     * @deprecated Use {@link #revokeServiceAccount} for new code.
     */
    @Deprecated(forRemoval = true)
    default void revokeApiToken(String tenantId, String keyId) {
        throw new UnsupportedOperationException(
                "API key revocation is deprecated — use revokeServiceAccount()");
    }
}
