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
     * @param username the user's login name
     * @param email the user's email
     * @param realmId the realm the user was registered in
     */
    record RegisterUserResult(String username, String email, String realmId) {}

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
     * @param username the user's unique login name
     * @param email the user's email address
     * @param fullName the user's display name
     * @param roles initial roles to assign
     * @param temporaryPassword if true, user must change password on first login
     * @return the registration result
     */
    RegisterUserResult registerUser(
            String realmId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword);

    /**
     * Suspend a user account. Publishes a {@code UserSuspendedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param emailOrUsername the user's email or username
     */
    void suspendUser(String realmId, String emailOrUsername);

    /**
     * Re-activate a suspended user account. Publishes a {@code UserActivatedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param emailOrUsername the user's email or username
     */
    void activateUser(String realmId, String emailOrUsername);

    /**
     * Create a service account (confidential client with client credentials grant). Publishes a
     * {@code ServiceAccountCreatedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param name human-readable name for the service account
     * @param roles roles to assign to the service account
     * @return the result containing the one-time client secret
     */
    CreateServiceAccountResult createServiceAccount(String realmId, String name, Set<String> roles);

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
     * @param emailOrUsername the user's email or username
     * @param role the role name to assign
     */
    void assignRole(String realmId, String emailOrUsername, String role);

    /**
     * Remove a role from a user. Publishes a {@code RoleRemovedEvent}.
     *
     * @param realmId the Keycloak realm
     * @param emailOrUsername the user's email or username
     * @param role the role name to remove
     */
    void removeRole(String realmId, String emailOrUsername, String role);

    // -- Organization management (single realm + Keycloak Organizations) -------

    /** Result of creating an organization. */
    record CreateOrganizationResult(String orgId, String orgName) {}

    /**
     * Create a Keycloak Organization (tenant).
     *
     * @param orgName unique organization name (used as tenant identifier)
     * @param displayName human-readable display name
     * @return the created organization's metadata
     */
    default CreateOrganizationResult createOrganization(String orgName, String displayName) {
        throw new UnsupportedOperationException("createOrganization not implemented");
    }

    /**
     * Add a user to a Keycloak Organization.
     *
     * @param orgId the Keycloak organization ID
     * @param userId the Keycloak user ID
     */
    default void addOrganizationMember(String orgId, String userId) {
        throw new UnsupportedOperationException("addOrganizationMember not implemented");
    }

    /**
     * Remove a user from a Keycloak Organization.
     *
     * @param orgId the Keycloak organization ID
     * @param userId the Keycloak user ID
     */
    default void removeOrganizationMember(String orgId, String userId) {
        throw new UnsupportedOperationException("removeOrganizationMember not implemented");
    }

    /**
     * Delete a Keycloak Organization (tenant).
     *
     * @param orgId the Keycloak organization ID
     */
    default void deleteOrganization(String orgId) {
        throw new UnsupportedOperationException("deleteOrganization not implemented");
    }

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
