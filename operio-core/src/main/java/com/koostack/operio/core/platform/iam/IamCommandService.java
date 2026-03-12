package com.koostack.operio.core.platform.iam;

import com.koostack.operio.core.spi.Plugin;
import java.time.Instant;
import java.util.Set;

/**
 * Command service for IAM operations — registers users, manages access tokens, and assigns roles.
 *
 * <p>Each command publishes a domain event to the event store. The projection handler translates
 * these events into Keycloak Admin API calls or database writes as appropriate.
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
     * Result of creating a personal access token.
     *
     * @param plaintextToken the full token (shown once only — store securely)
     * @param tokenPrefix the display prefix for identification
     */
    record CreateAccessTokenResult(String plaintextToken, String tokenPrefix) {}

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
     * Create a personal access token (PAT) scoped to a tenant.
     *
     * <p>The token carries a snapshot of the creator's Keycloak roles and expires at the given
     * time. Publishes an {@code AccessTokenCreatedEvent}.
     *
     * @param tenantId the tenant this token is scoped to
     * @param name human-readable name for the token
     * @param roles comma-separated Keycloak roles to embed
     * @param createdBy the Keycloak user ID (subject) of the creator
     * @param expiresAt when the token should expire (max 90 days from now)
     * @return the result containing the one-time plaintext token
     */
    CreateAccessTokenResult createAccessToken(
            String tenantId, String name, String roles, String createdBy, Instant expiresAt);

    /**
     * Revoke a personal access token. Publishes an {@code AccessTokenRevokedEvent}.
     *
     * @param tenantId the tenant this token is scoped to
     * @param tokenPrefix the display prefix identifying the token to revoke
     */
    void revokeAccessToken(String tenantId, String tokenPrefix);

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
}
