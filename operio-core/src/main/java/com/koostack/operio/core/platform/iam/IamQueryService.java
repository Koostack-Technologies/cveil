package com.koostack.operio.core.platform.iam;

import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.spi.Plugin;
import java.util.List;
import java.util.Set;

/**
 * Query service for IAM operations — reads users, access tokens, and roles from Keycloak and the
 * platform database.
 *
 * <p>Also provides authentication (token exchange) and token verification operations since these
 * are read-side operations that query the IdP.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface IamQueryService<T> extends Plugin<T> {

    // -- User queries ---------------------------------------------------------

    /**
     * List all users in the given realm.
     *
     * @param realmId the Keycloak realm
     * @return list of users
     */
    List<IdpUser> listUsers(String realmId);

    /**
     * Get a single user by ID.
     *
     * @param realmId the Keycloak realm
     * @param userId the user's Keycloak ID
     * @return the user, or {@code null} if not found
     */
    IdpUser getUser(String realmId, String userId);

    /**
     * Find a user by email address.
     *
     * @param realmId the Keycloak realm
     * @param email the email to search for
     * @return the user, or {@code null} if not found
     */
    IdpUser findUserByEmail(String realmId, String email);

    /**
     * Find a user by username.
     *
     * @param realmId the Keycloak realm
     * @param username the username to search for
     * @return the user, or {@code null} if not found
     */
    IdpUser findUserByUsername(String realmId, String username);

    /**
     * Get all roles assigned to a user (by internal ID).
     *
     * @param realmId the Keycloak realm
     * @param userId the user's internal ID (opaque)
     * @return the user's roles, possibly empty — never {@code null}
     */
    Set<String> getUserRoles(String realmId, String userId);

    /**
     * Get all roles assigned to a user identified by email.
     *
     * @param realmId the Keycloak realm
     * @param email the user's email
     * @return the user's roles, possibly empty — never {@code null}
     */
    Set<String> getUserRolesByEmail(String realmId, String email);

    /**
     * Get all roles assigned to a user identified by username.
     *
     * @param realmId the Keycloak realm
     * @param username the user's username
     * @return the user's roles, possibly empty — never {@code null}
     */
    Set<String> getUserRolesByUsername(String realmId, String username);

    /**
     * Resolve a user's internal ID from an email or username. Used internally by command services
     * to map external identifiers to event-store aggregate keys.
     *
     * @param realmId the Keycloak realm
     * @param emailOrUsername the user's email or username
     * @return the internal user ID, or {@code null} if not found
     */
    String resolveUserId(String realmId, String emailOrUsername);

    // -- Access token queries -------------------------------------------------

    /**
     * List all access tokens for a tenant.
     *
     * @param tenantId the tenant ID
     * @return list of access token metadata (never contains raw tokens or hashes)
     */
    List<AccessTokenInfo> listAccessTokens(String tenantId);

    /**
     * List access tokens created by a specific user within a tenant.
     *
     * @param tenantId the tenant ID
     * @param createdBy the Keycloak user ID of the creator
     * @return list of access token metadata
     */
    List<AccessTokenInfo> listAccessTokensByCreator(String tenantId, String createdBy);

    /**
     * Get a single access token by tenant and prefix.
     *
     * @param tenantId the tenant ID
     * @param tokenPrefix the display prefix (e.g. {@code cveil_a8Kx3mNp})
     * @return the token metadata, or {@code null} if not found
     */
    AccessTokenInfo getAccessToken(String tenantId, String tokenPrefix);

    /**
     * Look up an access token by its SHA-256 hash. Used by the auth filter hot path.
     *
     * @param tokenHash the SHA-256 hex digest of the raw token
     * @return the token metadata, or {@code null} if not found
     */
    AccessTokenInfo findAccessTokenByHash(String tokenHash);

    // -- Organization queries (single realm + Keycloak Organizations) ----------

    /** Organization metadata returned by queries. */
    record OrganizationInfo(String orgId, String orgName, String displayName, boolean enabled) {}

    /** Member metadata returned by org member queries. */
    record OrgMemberInfo(String userId, String username, String email) {}

    /**
     * List all organizations (tenants).
     *
     * @param offset pagination offset
     * @param limit maximum results
     * @return list of organizations
     */
    default java.util.List<OrganizationInfo> listOrganizations(int offset, int limit) {
        throw new UnsupportedOperationException("listOrganizations not implemented");
    }

    /**
     * Get a single organization by ID.
     *
     * @param orgId the Keycloak organization ID
     * @return the organization, or {@code null} if not found
     */
    default OrganizationInfo getOrganization(String orgId) {
        throw new UnsupportedOperationException("getOrganization not implemented");
    }

    /**
     * List members of an organization.
     *
     * @param orgId the Keycloak organization ID
     * @param offset pagination offset
     * @param limit maximum results
     * @return list of members
     */
    default java.util.List<OrgMemberInfo> listOrganizationMembers(
            String orgId, int offset, int limit) {
        throw new UnsupportedOperationException("listOrganizationMembers not implemented");
    }

    // -- Authentication -------------------------------------------------------

    /**
     * Authenticate a user with email and password (ROPC grant).
     *
     * @param realmId the Keycloak realm
     * @param email the user's email
     * @param password the user's password
     * @return tokens and identity context
     */
    AuthResult authenticate(String realmId, String email, String password);

    /**
     * Refresh an access token.
     *
     * @param realmId the Keycloak realm
     * @param refreshToken the refresh token
     * @return new tokens and identity context
     */
    AuthResult refreshToken(String realmId, String refreshToken);

    /**
     * Verify a JWT access token and return the identity.
     *
     * @param realmId the Keycloak realm
     * @param accessToken the JWT access token
     * @return the verified identity context
     */
    IdentityContext verifyToken(String realmId, String accessToken);

    /**
     * Logout — revoke the given refresh token.
     *
     * @param realmId the Keycloak realm
     * @param refreshToken the refresh token to revoke
     */
    void logout(String realmId, String refreshToken);
}
