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
 * <p>Also orchestrates tenant onboarding: creating a tenant, billing
 * profile, and initial subscription as part of user registration.
 *
 * <p>IAM methods delegate to the configured
 * {@link com.koostack.operio.core.platform.iam.IdpProvider}.
 * Tenant/billing methods delegate to
 * {@link com.koostack.operio.core.platform.core.CoreCommandService} and
 * {@link com.koostack.operio.core.platform.billing.BillingCommandService}.
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

    // -----------------------------------------------------------------------
    // Tenant Onboarding
    // -----------------------------------------------------------------------

    /**
     * Create a new tenant and publish a {@code TenantCreated} domain event.
     *
     * @param tenantId    the unique tenant identifier
     * @param displayName human-readable display name
     * @param plan        the initial plan tier (e.g. "free", "pro")
     */
    void createTenant(String tenantId, String displayName, String plan);

    /**
     * Create a billing profile for the given tenant and publish a
     * {@code BillingProfileCreated} domain event.
     *
     * @param tenantId     the tenant identifier
     * @param billingEmail the billing contact email
     */
    void createBillingProfile(String tenantId, String billingEmail);

    /**
     * Create an initial subscription for the given tenant and publish a
     * {@code SubscriptionCreated} domain event.
     *
     * @param tenantId     the tenant identifier
     * @param planTier     the subscription plan tier
     * @param evalLimit    monthly evaluation limit
     * @param subjectLimit subject limit
     * @param burstLimit   burst limit per minute
     */
    void createSubscription(String tenantId, String planTier,
                            int evalLimit, int subjectLimit, int burstLimit);
}
