package com.koostack.cveil.rest.lifecycle;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.platform.billing.jooq.CreateBillingProfileCommandActuator;
import com.koostack.cveil.platform.billing.jooq.CreateSubscriptionCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.SeedConsentGovernanceCommandActuator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the deferred portion of user registration asynchronously.
 *
 * <p>After the fast synchronous signup (Keycloak user, tenant, roles, Redis), this task handles the
 * slower steps in a background thread:
 *
 * <ol>
 *   <li><b>Seed consent governance</b> — seeds default US governance data (~12s due to projection
 *       catch-up waits)
 *   <li><b>Create billing profile</b>
 *   <li><b>Create subscription</b> — enrolls the tenant on the free plan
 * </ol>
 *
 * <p>Progress is reported via the injected {@link ProvisioningTracker}. The "Setting up workspace"
 * loading page polls the tracker for status updates.
 */
public class DeferredProvisioningTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(DeferredProvisioningTask.class);

    private static final int TOTAL_STEPS = 3;

    private final String tenantId;
    private final String email;
    private final String plan;
    private final CommandDispatcher consentDispatcher;
    private final CommandDispatcher billingDispatcher;
    private final ProvisioningTracker tracker;

    public DeferredProvisioningTask(
            String tenantId,
            String email,
            String plan,
            CommandDispatcher consentDispatcher,
            CommandDispatcher billingDispatcher,
            ProvisioningTracker tracker) {
        this.tenantId = tenantId;
        this.email = email;
        this.plan = plan;
        this.consentDispatcher = consentDispatcher;
        this.billingDispatcher = billingDispatcher;
        this.tracker = tracker;
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

            // Step 3: Create subscription
            tracker.updateStep(tenantId, "Activating your plan", 2, TOTAL_STEPS);
            createSubscription();

            tracker.complete(tenantId);
            LOG.info("Deferred provisioning completed for tenant: {}", tenantId);

        } catch (Exception e) {
            LOG.error(
                    "Deferred provisioning failed for tenant {}: {}",
                    tenantId,
                    e.getMessage(),
                    e);
            tracker.fail(tenantId, "Workspace setup encountered an error. Please contact support.");
        }
    }

    private void seedConsentGovernance() {
        if (consentDispatcher == null) {
            LOG.info("Consent dispatcher not available — skipping governance seed");
            return;
        }
        try {
            consentDispatcher.dispatch(
                    new SeedConsentGovernanceCommandActuator.SeedConsentGovernanceCommand(tenantId));
        } catch (Exception e) {
            // Non-fatal: governance seed failure should not block the rest of provisioning
            LOG.warn("Consent governance seed failed (non-fatal) for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    private void createBillingProfile() {
        billingDispatcher.dispatch(
                new CreateBillingProfileCommandActuator.CreateBillingProfileCommand(
                        tenantId, email));
    }

    private void createSubscription() {
        billingDispatcher.dispatch(
                new CreateSubscriptionCommandActuator.CreateSubscriptionCommand(tenantId, plan));
    }
}
