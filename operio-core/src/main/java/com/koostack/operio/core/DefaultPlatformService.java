package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.billing.BillingCommandService;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.iam.IdpProvider;

import java.util.logging.Logger;

/**
 * Default {@link PlatformService} implementation that delegates IAM
 * operations to the configured {@link IdpProvider} and tenant/billing
 * onboarding to {@link CoreCommandService} / {@link BillingCommandService}.
 *
 * <p>All methods take an explicit {@code tenantId} — unlike
 * {@link DefaultTenantService} which uses {@code TenantContext}.
 */
public class DefaultPlatformService implements PlatformService {

    private static final Logger LOG = Logger.getLogger(
        DefaultPlatformService.class.getName()
    );

    private final String appName;
    private final IdpProvider<?> idpProvider;
    private final CoreCommandService<?> coreCommandService;
    private final BillingCommandService<?> billingCommandService;

    public DefaultPlatformService(
            String appName,
            IdpProvider<?> idpProvider,
            CoreCommandService<?> coreCommandService,
            BillingCommandService<?> billingCommandService) {
        if (idpProvider == null) {
            throw new OperioConfigurationException(
                "DefaultPlatformService requires a non-null IdpProvider"
            );
        }
        this.appName = appName != null ? appName : Operio.DEFAULT_APP_NAME;
        this.idpProvider = idpProvider;
        this.coreCommandService = coreCommandService;
        this.billingCommandService = billingCommandService;
    }

    /**
     * Backward-compatible constructor for callers that only need IAM.
     */
    public DefaultPlatformService(String appName, IdpProvider<?> idpProvider) {
        this(appName, idpProvider, null, null);
    }

    // -----------------------------------------------------------------------
    // OperioService
    // -----------------------------------------------------------------------

    @Override
    public String appName() {
        return appName;
    }

    @Override
    public boolean isReady() {
        return idpProvider.isReady();
    }

    @Override
    public void verifyReady() {
        if (!isReady()) {
            throw new OperioConfigurationException(
                "PlatformService IdpProvider '" + idpProvider.getName() + "' is not ready"
            );
        }
    }

    // -----------------------------------------------------------------------
    // IAM — User Management
    // -----------------------------------------------------------------------

    @Override
    public IdpUser registerUser(String tenantId, String email, String password, String fullName) {
        return idpProvider.registerUser(tenantId, email, password, fullName);
    }

    @Override
    public void verifyEmail(String tenantId, String userId, String verificationToken) {
        idpProvider.verifyEmail(tenantId, userId, verificationToken);
    }

    @Override
    public void initiatePasswordReset(String tenantId, String email) {
        idpProvider.initiatePasswordReset(tenantId, email);
    }

    @Override
    public void completePasswordReset(String tenantId, String resetToken, String newPassword) {
        idpProvider.completePasswordReset(tenantId, resetToken, newPassword);
    }

    // -----------------------------------------------------------------------
    // Tenant Onboarding
    // -----------------------------------------------------------------------

    @Override
    public void createTenant(String tenantId, String displayName, String plan) {
        requireCoreCommand();
        coreCommandService.createTenant(tenantId, displayName, plan);
    }

    @Override
    public void createBillingProfile(String tenantId, String billingEmail) {
        requireBillingCommand();
        billingCommandService.createBillingProfile(tenantId, billingEmail);
    }

    @Override
    public void createSubscription(String tenantId, String planTier,
                                   int evalLimit, int subjectLimit, int burstLimit) {
        requireBillingCommand();
        billingCommandService.createSubscription(tenantId, planTier, evalLimit, subjectLimit, burstLimit);
    }

    private void requireCoreCommand() {
        if (coreCommandService == null) {
            throw new OperioConfigurationException(
                "CoreCommandService is not configured — tenant onboarding is unavailable"
            );
        }
    }

    private void requireBillingCommand() {
        if (billingCommandService == null) {
            throw new OperioConfigurationException(
                "BillingCommandService is not configured — billing onboarding is unavailable"
            );
        }
    }
}
