package com.koostack.cveil.site.lifecycle;

import com.koostack.cveil.core.platform.billing.PaymentProcessorService;
import com.koostack.cveil.core.platform.plan.PlanQueryService;
import com.koostack.cveil.core.platform.plan.PlanQueryService.PlanInfo;
import com.koostack.cveil.site.config.PlatformCqrsHolder;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled reconciliation task that detects drift between local plan data and Stripe.
 *
 * <p>Runs daily at 2 AM and performs a non-destructive check:
 *
 * <ul>
 *   <li>Reads all active billable plans from the database
 *   <li>Calls the idempotent {@link PaymentProcessorService#syncProduct} for each
 *   <li>Logs warnings when local Stripe IDs are missing or differ from what Stripe returns
 * </ul>
 *
 * <p>This task does <em>not</em> auto-correct drift — it logs for operator review. The {@link
 * StripePlanSyncTask} startup sync handles correction on the next deployment.
 */
@ApplicationScoped
public class StripeReconciliationTask {

    private static final Logger LOG = LoggerFactory.getLogger(StripeReconciliationTask.class);

    @Inject PlatformCqrsHolder cqrs;

    @Scheduled(cron = "0 0 2 * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcile() {
        if (cqrs == null) {
            return;
        }
        PaymentProcessorService<?> processor = cqrs.paymentProcessorService();
        if (processor == null) {
            return;
        }
        PlanQueryService<?> planQuery = cqrs.planQueryService();
        if (planQuery == null || !planQuery.isReady()) {
            LOG.debug("PlanQueryService not ready — skipping reconciliation");
            return;
        }

        List<PlanInfo> plans = planQuery.listActivePlans();
        int checked = 0;
        int driftDetected = 0;

        for (PlanInfo plan : plans) {
            Integer priceCents = plan.monthlyPriceCents();
            if (priceCents == null) {
                continue;
            }

            try {
                var mapping =
                        processor.syncProduct(
                                plan.planId(), plan.displayName(), priceCents, "month");

                boolean productDrift =
                        plan.stripeProductId() != null
                                && !plan.stripeProductId().isBlank()
                                && !plan.stripeProductId().equals(mapping.externalProductId());
                boolean priceDrift =
                        plan.stripePriceId() != null
                                && !plan.stripePriceId().isBlank()
                                && !plan.stripePriceId().equals(mapping.externalPriceId());

                if (productDrift || priceDrift) {
                    LOG.warn(
                            "Stripe drift detected for plan {}: "
                                    + "local product={} stripe={}, local price={} stripe={}",
                            plan.planId(),
                            plan.stripeProductId(),
                            mapping.externalProductId(),
                            plan.stripePriceId(),
                            mapping.externalPriceId());
                    driftDetected++;
                }
                checked++;
            } catch (Exception e) {
                LOG.warn(
                        "Reconciliation check failed for plan {}: {}",
                        plan.planId(),
                        e.getMessage());
            }
        }

        if (driftDetected > 0) {
            LOG.warn("Stripe reconciliation: {}/{} plans have drift", driftDetected, checked);
        } else {
            LOG.info(
                    "Stripe reconciliation: {}/{} billable plans verified — no drift",
                    checked,
                    plans.size());
        }
    }
}
