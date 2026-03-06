package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.billing.BillingCommandService;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.iam.IdpProvider;
import com.koostack.operio.core.platform.plan.PlanQueryService;

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
    private final CoreQueryService<?> coreQueryService;
    private final PlanQueryService<?> planQueryService;
    private final MetricPublisherService<?> platformMetricPublisher;
    private final MetricPublisherService<?> tenantMetricPublisher;

    public DefaultPlatformService(
            String appName,
            IdpProvider<?> idpProvider,
            CoreCommandService<?> coreCommandService,
            BillingCommandService<?> billingCommandService,
            CoreQueryService<?> coreQueryService,
            PlanQueryService<?> planQueryService,
            MetricPublisherService<?> platformMetricPublisher,
            MetricPublisherService<?> tenantMetricPublisher) {
        this.appName = appName != null ? appName : Operio.DEFAULT_APP_NAME;
        this.idpProvider = idpProvider;
        this.coreCommandService = coreCommandService;
        this.billingCommandService = billingCommandService;
        this.coreQueryService = coreQueryService;
        this.planQueryService = planQueryService;
        this.platformMetricPublisher = platformMetricPublisher;
        this.tenantMetricPublisher = tenantMetricPublisher;
    }

    /**
     * Backward-compatible constructor for callers that only need IAM.
     */
    public DefaultPlatformService(String appName, IdpProvider<?> idpProvider) {
        this(appName, idpProvider, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor without query services and metrics.
     */
    public DefaultPlatformService(
            String appName,
            IdpProvider<?> idpProvider,
            CoreCommandService<?> coreCommandService,
            BillingCommandService<?> billingCommandService) {
        this(appName, idpProvider, coreCommandService, billingCommandService,
             null, null, null, null);
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
        return idpProvider == null || idpProvider.isReady();
    }

    @Override
    public void verifyReady() {
        if (idpProvider != null && !idpProvider.isReady()) {
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
    // Tenant Onboarding (orchestrated)
    // -----------------------------------------------------------------------

    @Override
    public void createTenant(String tenantId, String displayName, String plan, String billingEmail) {
        requireCoreCommand();
        requireBillingCommand();

        // 1. Validate tenantId uniqueness
        if (coreQueryService != null) {
            var existing = coreQueryService.getTenant(tenantId);
            if (existing != null) {
                throw new OperioServiceException(
                    "Tenant '" + tenantId + "' already exists");
            }
        }

        // 2. Validate plan exists and is active
        if (planQueryService != null) {
            var planInfo = planQueryService.getPlan(plan);
            if (planInfo == null) {
                throw new OperioServiceException(
                    "Plan '" + plan + "' does not exist");
            }
            if (!planInfo.isActive()) {
                throw new OperioServiceException(
                    "Plan '" + plan + "' is not active");
            }

            // 3. Create tenant
            coreCommandService.createTenant(tenantId, displayName, plan);

            // 4. Create billing profile
            billingCommandService.createBillingProfile(tenantId, billingEmail);

            // 5. Create subscription using plan limits
            //    BillingCommandService uses int limits; safe for current plan values.
            billingCommandService.createSubscription(
                tenantId, plan,
                Math.toIntExact(planInfo.evalLimit()),
                Math.toIntExact(planInfo.subjectLimit()),
                planInfo.burstLimit());
        } else {
            // Fallback: no plan validation available
            coreCommandService.createTenant(tenantId, displayName, plan);
            billingCommandService.createBillingProfile(tenantId, billingEmail);
            billingCommandService.createSubscription(tenantId, plan, 0, 0, 0);
        }

        // 6. Emit TenantProvisioningStarted (triggers async provisioning)
        coreCommandService.emitProvisioningStarted(tenantId, displayName, plan);

        LOG.info("Tenant onboarding initiated: tenantId=" + tenantId + ", plan=" + plan);
    }

    // -----------------------------------------------------------------------
    // Individual billing operations (kept for standalone use)
    // -----------------------------------------------------------------------

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

    @Override
    public void changeTenantPlan(String tenantId, String newPlan) {
        requireCoreCommand();
        String oldPlan = null;
        if (coreQueryService != null) {
            var tenant = coreQueryService.getTenant(tenantId);
            if (tenant != null) {
                oldPlan = tenant.plan();
            }
        }
        coreCommandService.changeTenantPlan(tenantId, oldPlan, newPlan);
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
