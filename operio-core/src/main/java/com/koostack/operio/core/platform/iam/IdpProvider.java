package com.koostack.operio.core.platform.iam;

import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.spi.Plugin;

/**
 * Identity provider abstraction — the core SPI for authentication, user management, token
 * verification, and tenant lifecycle.
 *
 * <p>Role management (authorization) is handled by the separate {@link
 * com.koostack.operio.core.tenant.authz.AuthzProvider} SPI.
 *
 * <p>The platform defaults to a Keycloak implementation, but tenants can override with any IdP
 * (Auth0, Okta, Azure AD, etc.) by configuring a different {@code IdpProvider} via the {@link
 * IdpTenantResolver}.
 *
 * <h2>Known implementations</h2>
 *
 * <ul>
 *   <li>{@code KeycloakIdpProvider} (operio-platform-keycloak) — manages a single shared Keycloak
 *       realm with Organizations for tenant isolation. Delegates auth via the Keycloak Admin REST
 *       API and token endpoint.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>Implementations must be thread-safe. A single instance is shared across all request threads.
 *
 * @param <T> the plugin configuration type
 * @see IdpTenantResolver
 * @see TokenValidationService
 */
public interface IdpProvider<T> extends Plugin<T> {

    /**
     * Returns a sentinel {@code IdpProvider} that throws on every operation.
     *
     * <p>Used as a null-safe placeholder when no IdP is configured (e.g. embedded mode). CDI
     * {@code @ApplicationScoped} producers must not return {@code null} — this sentinel avoids
     * that while producing clear errors if any code path accidentally attempts authentication.
     */
    @SuppressWarnings("unchecked")
    static <T> IdpProvider<T> unavailable() {
        return (IdpProvider<T>) UnavailableIdpProvider.INSTANCE;
    }

    /** Returns {@code true} if this is a real (configured) identity provider. */
    default boolean isAvailable() {
        return true;
    }

    // -------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------

    /**
     * Authenticate a user with email and password.
     *
     * @param tenantId the tenant realm to authenticate against
     * @param email the user's email address
     * @param password the user's password
     * @return tokens and identity context on success
     * @throws com.koostack.operio.core.exception.OperioAuthException if credentials are invalid or
     *     the account is not active
     */
    AuthResult authenticate(String tenantId, String email, String password);

    /**
     * Refresh an access token using a refresh token.
     *
     * @param tenantId the tenant realm
     * @param refreshToken the refresh token issued during login
     * @return new tokens and identity context
     * @throws com.koostack.operio.core.exception.OperioAuthException if the refresh token is
     *     invalid, expired, or revoked
     */
    AuthResult refreshToken(String tenantId, String refreshToken);

    /**
     * Logout — revoke the given refresh token.
     *
     * @param tenantId the tenant realm
     * @param refreshToken the refresh token to revoke
     */
    void logout(String tenantId, String refreshToken);

    // -------------------------------------------------------------------
    // User Management
    // -------------------------------------------------------------------

    /**
     * Register a new user.
     *
     * <p>The user is created in {@code unverified} status. A verification email should be sent via
     * the {@link EmailService} SPI.
     *
     * @param tenantId the tenant realm
     * @param email the user's email address
     * @param password the user's chosen password
     * @param fullName the user's display name
     * @return the created user (status will be {@code unverified})
     * @throws com.koostack.operio.core.exception.OperioServiceException if the email is already
     *     registered
     */
    IdpUser registerUser(String tenantId, String email, String password, String fullName);

    /**
     * Mark a user's email as verified.
     *
     * @param tenantId the tenant realm
     * @param userId the user's IdP identifier
     * @param verificationToken the token from the verification email
     * @throws com.koostack.operio.core.exception.OperioAuthException if the token is invalid or
     *     expired
     */
    void verifyEmail(String tenantId, String userId, String verificationToken);

    /**
     * Initiate a password reset flow — generates a reset token and sends a reset email via the
     * {@link EmailService} SPI.
     *
     * <p>Implementations should not reveal whether the email exists (to prevent user enumeration).
     *
     * @param tenantId the tenant realm
     * @param email the user's email address
     */
    void initiatePasswordReset(String tenantId, String email);

    /**
     * Complete a password reset using the token from the reset email.
     *
     * @param tenantId the tenant realm
     * @param resetToken the token from the password reset email
     * @param newPassword the user's new password
     * @throws com.koostack.operio.core.exception.OperioAuthException if the reset token is invalid
     *     or expired
     */
    void completePasswordReset(String tenantId, String resetToken, String newPassword);

    // -------------------------------------------------------------------
    // Token Verification (hot path)
    // -------------------------------------------------------------------

    /**
     * Verify a JWT access token and return the associated identity.
     *
     * <p>This is the request hot path — called on every authenticated request. Implementations
     * should use cached JWKS keys for performance.
     *
     * @param tenantId the tenant realm (used to resolve the JWKS endpoint)
     * @param accessToken the JWT access token from the Authorization header
     * @return the verified identity context — never {@code null}
     * @throws com.koostack.operio.core.exception.OperioAuthException if the token is invalid,
     *     expired, or the signature fails
     */
    IdentityContext verifyToken(String tenantId, String accessToken);

    // -------------------------------------------------------------------
    // Realm / Tenant Lifecycle (deprecated — single realm with organizations)
    // -------------------------------------------------------------------

    /**
     * @deprecated Per-tenant realms eliminated. Use Keycloak Organizations instead. This method is
     *     a no-op in the single-realm architecture.
     */
    @Deprecated(forRemoval = true)
    default void provisionTenant(String tenantId, String displayName) {
        // no-op — organizations replace per-tenant realms
    }

    /**
     * @deprecated Per-tenant realms eliminated. Use Keycloak Organizations instead. This method is
     *     a no-op in the single-realm architecture.
     */
    @Deprecated(forRemoval = true)
    default void deprovisionTenant(String tenantId) {
        // no-op — organizations replace per-tenant realms
    }
}
