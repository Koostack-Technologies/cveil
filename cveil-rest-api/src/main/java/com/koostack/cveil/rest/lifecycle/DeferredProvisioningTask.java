package com.koostack.cveil.rest.lifecycle;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.platform.billing.PaymentProcessorService;
import com.koostack.cveil.platform.billing.jooq.CreateBillingProfileCommandActuator;
import com.koostack.cveil.platform.billing.jooq.CreateSubscriptionCommandActuator;
import com.koostack.cveil.platform.billing.jooq.SetPaymentProcessorCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.SeedConsentGovernanceCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.SeedParameterizedGovernanceCommandActuator;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the deferred portion of user registration asynchronously.
 *
 * <p>After the fast synchronous signup (Keycloak user, tenant, roles, Redis), this task handles the
 * slower steps in a background thread:
 *
 * <ol>
 *   <li><b>Seed consent governance</b> — seeds governance data (~12s due to projection catch-up
 *       waits). When onboarding selections are provided, seeds only the selected jurisdictions,
 *       derived regulations, and preset purposes. Otherwise falls back to the full US governance
 *       seed.
 *   <li><b>Create billing profile</b>
 *   <li><b>Create Stripe customer</b> — creates the customer in Stripe and links to billing profile
 *   <li><b>Create subscription</b> — enrolls the tenant on the free plan
 * </ol>
 *
 * <p>Progress is reported via the injected {@link ProvisioningTracker}. The onboarding loading page
 * polls the tracker for status updates.
 */
public class DeferredProvisioningTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(DeferredProvisioningTask.class);

    private static final int TOTAL_STEPS = 4;

    /** Tenant ID that seed and billing data are written to (may be an org tenant). */
    private final String tenantId;

    /**
     * Tenant ID used to report progress via {@link ProvisioningTracker} (always the personal
     * tenant).
     */
    private final String trackingTenantId;

    private final String email;
    private final String plan;
    private final CommandDispatcher consentDispatcher;
    private final CommandDispatcher billingDispatcher;
    private final PaymentProcessorService<?> paymentProcessorService;
    private final ProvisioningTracker tracker;

    // Parameterized governance selections (null = use legacy full seed)
    private final Set<String> jurisdictionIds;
    private final Set<String> regulationIds;
    private final Set<String> purposeIds;
    private final Set<String> entityNames;

    /** Full constructor with onboarding selections for parameterized seeding. */
    public DeferredProvisioningTask(
            String tenantId,
            String trackingTenantId,
            String email,
            String plan,
            CommandDispatcher consentDispatcher,
            CommandDispatcher billingDispatcher,
            PaymentProcessorService<?> paymentProcessorService,
            ProvisioningTracker tracker,
            Set<String> jurisdictionIds,
            Set<String> regulationIds,
            Set<String> purposeIds,
            Set<String> entityNames) {
        this.tenantId = tenantId;
        this.trackingTenantId = trackingTenantId;
        this.email = email;
        this.plan = plan;
        this.consentDispatcher = consentDispatcher;
        this.billingDispatcher = billingDispatcher;
        this.paymentProcessorService = paymentProcessorService;
        this.tracker = tracker;
        this.jurisdictionIds = jurisdictionIds;
        this.regulationIds = regulationIds;
        this.purposeIds = purposeIds;
        this.entityNames = entityNames;
    }

    /** Legacy constructor — falls back to full governance seed under the personal tenant. */
    public DeferredProvisioningTask(
            String tenantId,
            String email,
            String plan,
            CommandDispatcher consentDispatcher,
            CommandDispatcher billingDispatcher,
            PaymentProcessorService<?> paymentProcessorService,
            ProvisioningTracker tracker) {
        this(
                tenantId,
                tenantId,
                email,
                plan,
                consentDispatcher,
                billingDispatcher,
                paymentProcessorService,
                tracker,
                null,
                null,
                null,
                null);
    }

    /** Returns the total number of deferred steps (for tracker initialization). */
    public static int totalSteps() {
        return TOTAL_STEPS;
    }

    @Override
    public void run() {
        LOG.info(
                "Starting deferred provisioning for tenant: {} (tracker: {})",
                tenantId,
                trackingTenantId);

        try {
            // Step 1: Seed consent governance data (the slow step)
            tracker.updateStep(trackingTenantId, "Configuring governance defaults", 0, TOTAL_STEPS);
            seedConsentGovernance();

            // Step 2: Create billing profile
            tracker.updateStep(trackingTenantId, "Setting up billing", 1, TOTAL_STEPS);
            createBillingProfile();

            // Step 3: Create Stripe customer and link to billing profile
            tracker.updateStep(trackingTenantId, "Connecting payment processor", 2, TOTAL_STEPS);
            createStripeCustomer();

            // Step 4: Create subscription
            tracker.updateStep(trackingTenantId, "Activating your plan", 3, TOTAL_STEPS);
            createSubscription();

            tracker.complete(trackingTenantId);
            LOG.info("Deferred provisioning completed for tenant: {}", tenantId);

        } catch (Exception e) {
            LOG.error(
                    "Deferred provisioning failed for tenant {}: {}", tenantId, e.getMessage(), e);
            String stepHint = inferFailedStep(e);
            tracker.fail(
                    trackingTenantId,
                    "Workspace setup failed during "
                            + stepHint
                            + ": "
                            + abbreviate(e.getMessage())
                            + ". Please contact support.");
        }
    }

    private void seedConsentGovernance() {
        if (consentDispatcher == null) {
            LOG.info("Consent dispatcher not available — skipping governance seed");
            return;
        }
        try {
            if (jurisdictionIds != null) {
                LOG.info(
                        "Seeding parameterized governance for tenant {}: jurisdictions={}, "
                                + "regulations={}, purposes={}, entities={}",
                        tenantId,
                        jurisdictionIds,
                        regulationIds,
                        purposeIds,
                        entityNames);
                consentDispatcher.dispatch(
                        new SeedParameterizedGovernanceCommandActuator
                                .SeedParameterizedGovernanceCommand(
                                tenantId, jurisdictionIds, regulationIds, purposeIds, entityNames));
                LOG.info("Parameterized governance seed completed for tenant {}", tenantId);
            } else {
                LOG.info("Seeding full governance for tenant {}", tenantId);
                consentDispatcher.dispatch(
                        new SeedConsentGovernanceCommandActuator.SeedConsentGovernanceCommand(
                                tenantId));
                LOG.info("Full governance seed completed for tenant {}", tenantId);
            }
        } catch (Exception e) {
            LOG.error(
                    "Consent governance seed FAILED for tenant {}: {}",
                    tenantId,
                    e.getMessage(),
                    e);
        }
    }

    private void createBillingProfile() {
        billingDispatcher.dispatch(
                new CreateBillingProfileCommandActuator.CreateBillingProfileCommand(
                        tenantId, email));
    }

    private void createStripeCustomer() {
        if (paymentProcessorService == null) {
            LOG.info("Payment processor not configured — skipping Stripe customer creation");
            return;
        }
        try {
            String customerId = paymentProcessorService.createCustomer(tenantId, email, tenantId);
            billingDispatcher.dispatch(
                    new SetPaymentProcessorCommandActuator.SetPaymentProcessorCommand(
                            tenantId, "stripe", customerId));
            LOG.info("Stripe customer {} created for tenant {}", customerId, tenantId);
        } catch (Exception e) {
            // Non-fatal: billing profile exists, Stripe customer can be created later
            LOG.warn(
                    "Stripe customer creation failed (non-fatal) for tenant {}: {}",
                    tenantId,
                    e.getMessage());
        }
    }

    private void createSubscription() {
        billingDispatcher.dispatch(
                new CreateSubscriptionCommandActuator.CreateSubscriptionCommand(
                        tenantId, plan, null, null, null));
    }

    /** Infers which provisioning step failed from the exception's stack trace. */
    private String inferFailedStep(Exception e) {
        String trace = stackTraceToString(e);
        if (trace.contains("seedConsentGovernance")
                || trace.contains("SeedConsent")
                || trace.contains("SeedParameterized")) {
            return "governance configuration";
        }
        if (trace.contains("createBillingProfile") || trace.contains("CreateBillingProfile")) {
            return "billing setup";
        }
        if (trace.contains("createStripeCustomer") || trace.contains("SetPaymentProcessor")) {
            return "payment processor setup";
        }
        if (trace.contains("createSubscription") || trace.contains("CreateSubscription")) {
            return "plan activation";
        }
        return "workspace setup";
    }

    private static String stackTraceToString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static String abbreviate(String msg) {
        if (msg == null) return "unknown error";
        return msg.length() > 120 ? msg.substring(0, 117) + "..." : msg;
    }
}
