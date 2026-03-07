package com.koostack.operio.core.platform.iam;

import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.spi.Plugin;
import java.util.List;
import java.util.Set;

/**
 * Query service for IAM operations — reads users, service accounts, and roles from Keycloak.
 *
 * <p>Also provides authentication (token exchange) and token verification operations since these
 * are read-side operations that query the IdP.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface IamQueryService<T> extends Plugin<T> {

    /** Projected service account metadata. */
    record ServiceAccountInfo(
            String clientId,
            String name,
            String realmId,
            Set<String> roles,
            String status,
            java.time.Instant createdAt) {}

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
     * Get all roles assigned to a user.
     *
     * @param realmId the Keycloak realm
     * @param userId the user's Keycloak ID
     * @return the user's roles, possibly empty — never {@code null}
     */
    Set<String> getUserRoles(String realmId, String userId);

    // -- Service account queries ----------------------------------------------

    /**
     * List all service accounts (confidential clients) in the given realm.
     *
     * @param realmId the Keycloak realm
     * @return list of service accounts
     */
    List<ServiceAccountInfo> listServiceAccounts(String realmId);

    /**
     * Get a single service account by client ID.
     *
     * @param realmId the Keycloak realm
     * @param clientId the Keycloak client ID
     * @return the service account, or {@code null} if not found
     */
    ServiceAccountInfo getServiceAccount(String realmId, String clientId);

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

    // -- Legacy API key methods (kept for backward compatibility) --------------

    /** @deprecated Use {@link ServiceAccountInfo} for new code. */
    @Deprecated(forRemoval = true)
    record ApiKeyInfo(
            String keyId,
            String keyPrefix,
            String name,
            String tenantId,
            String environment,
            String subject,
            String roles,
            String status,
            java.time.Instant createdAt,
            java.time.Instant expiresAt) {}

    /** @deprecated Use {@link #listServiceAccounts} for new code. */
    @Deprecated(forRemoval = true)
    default List<ApiKeyInfo> listApiKeys(String tenantId) {
        return List.of();
    }

    /** @deprecated Use {@link #getServiceAccount} for new code. */
    @Deprecated(forRemoval = true)
    default ApiKeyInfo getApiKey(String tenantId, String keyId) {
        return null;
    }

    /** @deprecated No longer needed — JWT verification replaces hash lookup. */
    @Deprecated(forRemoval = true)
    default ApiKeyInfo findByKeyHash(String keyHash) {
        return null;
    }

    /** @deprecated No longer needed — JWT verification replaces prefix lookup. */
    @Deprecated(forRemoval = true)
    default ApiKeyInfo findByKeyPrefix(String keyPrefix) {
        return null;
    }
}
