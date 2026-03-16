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

    private final String tenantId;
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

    /** Legacy constructor — falls back to full governance seed. */
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
        LOG.info("Starting deferred provisioning for tenant: {}", tenantId);

        try {
            // Step 1: Seed consent governance data (the slow step)
            tracker.updateStep(tenantId, "Configuring governance defaults", 0, TOTAL_STEPS);
            seedConsentGovernance();

            // Step 2: Create billing profile
            tracker.updateStep(tenantId, "Setting up billing", 1, TOTAL_STEPS);
            createBillingProfile();

            // Step 3: Create Stripe customer and link to billing profile
            tracker.updateStep(tenantId, "Connecting payment processor", 2, TOTAL_STEPS);
            createStripeCustomer();

            // Step 4: Create subscription
            tracker.updateStep(tenantId, "Activating your plan", 3, TOTAL_STEPS);
            createSubscription();

            tracker.complete(tenantId);
            LOG.info("Deferred provisioning completed for tenant: {}", tenantId);

        } catch (Exception e) {
            LOG.error(
                    "Deferred provisioning failed for tenant {}: {}", tenantId, e.getMessage(), e);
            tracker.fail(tenantId, "Workspace setup encountered an error. Please contact support.");
        }
    }

    private void seedConsentGovernance() {
        if (consentDispatcher == null) {
            LOG.info("Consent dispatcher not available — skipping governance seed");
            return;
        }
        try {
            if (jurisdictionIds != null) {
                // Parameterized seed — only the user's onboarding selections
                LOG.info(
                        "Seeding parameterized governance for tenant {}: {} jurisdictions",
                        tenantId,
                        jurisdictionIds.size());
                consentDispatcher.dispatch(
                        new SeedParameterizedGovernanceCommandActuator
                                .SeedParameterizedGovernanceCommand(
                                tenantId, jurisdictionIds, regulationIds, purposeIds, entityNames));
            } else {
                // Legacy full seed (API path or backward compatibility)
                consentDispatcher.dispatch(
                        new SeedConsentGovernanceCommandActuator.SeedConsentGovernanceCommand(
                                tenantId));
            }
        } catch (Exception e) {
            // Non-fatal: governance seed failure should not block the rest of provisioning
            LOG.warn(
                    "Consent governance seed failed (non-fatal) for tenant {}: {}",
                    tenantId,
                    e.getMessage());
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
}
