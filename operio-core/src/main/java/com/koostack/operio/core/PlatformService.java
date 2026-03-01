package com.koostack.operio.core;

import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdpUser;

/**
 * Platform-scoped application service for cross-tenant operations.
 *
 * <p>Handles identity management operations that are scoped by an
 * explicit {@code tenantId} parameter rather than the implicit
 * {@link com.koostack.operio.core.models.TenantContext} used by
 * {@link TenantService}.
 *
 * <p>All IAM methods delegate to the configured
 * {@link com.koostack.operio.core.platform.iam.IdpProvider}.
 */
public interface PlatformService extends OperioService {

    // -----------------------------------------------------------------------
    // IAM — User Management
    // -----------------------------------------------------------------------

    /**
     * Register a new user in the given tenant's identity provider.
     *
     * @param tenantId the tenant the user belongs to
     * @param email    the user's email address
     * @param password the user's initial password
     * @param fullName the user's display name, nullable
     * @return the created user representation
     */
    IdpUser registerUser(String tenantId, String email, String password, String fullName);

    /**
     * Verify a user's email address.
     *
     * @param tenantId          the tenant the user belongs to
     * @param userId            the user's IdP identifier
     * @param verificationToken the verification token
     */
    void verifyEmail(String tenantId, String userId, String verificationToken);

    /**
     * Initiate a password reset for the given email. Implementations
     * should not reveal whether the email exists.
     *
     * @param tenantId the tenant to search
     * @param email    the user's email address
     */
    void initiatePasswordReset(String tenantId, String email);

    /**
     * Complete a password reset using a reset token.
     *
     * @param tenantId    the tenant
     * @param resetToken  the reset token (or userId resolved from it)
     * @param newPassword the new password
     */
    void completePasswordReset(String tenantId, String resetToken, String newPassword);
}
