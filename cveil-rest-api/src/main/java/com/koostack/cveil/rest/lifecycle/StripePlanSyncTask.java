package com.koostack.cveil.rest.lifecycle;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.platform.billing.PaymentProcessorService;
import com.koostack.cveil.core.platform.billing.models.ProductPriceMapping;
import com.koostack.cveil.core.platform.plan.PlanQueryService;
import com.koostack.cveil.core.platform.plan.PlanQueryService.PlanInfo;
import com.koostack.cveil.platform.core.jooq.SyncPlanStripeIdsCommandActuator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotent startup task that ensures every billable plan has a corresponding Stripe Product +
 * Price.
 *
 * <p>On each application boot this task:
 *
 * <ol>
 *   <li>Reads all active plans from {@code cveilcore.plan}
 *   <li>Skips plans that already have Stripe IDs persisted (fast path)
 *   <li>Skips plans without pricing (custom/enterprise) based on {@code monthly_price_cents}
 *   <li>For plans that need syncing, calls {@link PaymentProcessorService#syncProduct} which is
 *       itself idempotent (searches Stripe by {@code metadata.plan_id} before creating)
 *   <li>Dispatches a {@code SyncPlanStripeIdsCommand} so the event store captures the mapping and
 *       the core projection materializes the Stripe IDs onto the plan read model
 * </ol>
 *
 * <p>Enterprise plans (custom pricing) are skipped entirely — they have no fixed price to sync.
 * Pricing is read from the {@code monthly_price_cents} and {@code annual_price_cents} columns in
 * the plan table (populated by V13 migration).
 */
public final class StripePlanSyncTask {

    private static final Logger LOG = LoggerFactory.getLogger(StripePlanSyncTask.class);

    private static final String MONTHLY_INTERVAL = "month";
    private static final String ANNUAL_INTERVAL = "year";

    private StripePlanSyncTask() {}

    /**
     * Run the idempotent plan → Stripe sync.
     *
     * @param paymentProcessor the configured payment processor (may be {@code null} if Stripe is
     *     not configured)
     * @param planQueryService the plan query service (reads current plan state)
     * @param coreDispatcher the core command dispatcher (publishes domain events)
     */
    @SuppressWarnings("rawtypes")
    public static void sync(
            PaymentProcessorService paymentProcessor,
            PlanQueryService planQueryService,
            CommandDispatcher coreDispatcher) {

        if (paymentProcessor == null) {
            LOG.info("PaymentProcessorService not configured — skipping Stripe plan sync");
            return;
        }
        if (planQueryService == null || !planQueryService.isReady()) {
            LOG.warn("PlanQueryService not ready — skipping Stripe plan sync");
            return;
        }

        List<PlanInfo> plans = planQueryService.listActivePlans();
        LOG.info("Stripe plan sync: {} active plans found", plans.size());

        int synced = 0;
        int skipped = 0;

        for (PlanInfo plan : plans) {
            Integer priceCents = plan.monthlyPriceCents();
            if (priceCents == null) {
                LOG.debug("Skipping plan {} — no fixed price (custom/enterprise)", plan.planId());
                skipped++;
                continue;
            }

            // Fast path: already fully synced — monthly + annual Stripe IDs present
            boolean hasMonthly =
                    plan.stripeProductId() != null
                            && !plan.stripeProductId().isBlank()
                            && plan.stripePriceId() != null
                            && !plan.stripePriceId().isBlank();
            boolean needsAnnual =
                    plan.hasAnnualPricing()
                            && (plan.stripeAnnualPriceId() == null
                                    || plan.stripeAnnualPriceId().isBlank());

            if (hasMonthly && !needsAnnual) {
                LOG.debug(
                        "Plan {} already synced (product={}, price={}, annual={})",
                        plan.planId(),
                        plan.stripeProductId(),
                        plan.stripePriceId(),
                        plan.stripeAnnualPriceId());
                skipped++;
                continue;
            }

            try {
                // Sync monthly price
                ProductPriceMapping monthlyMapping =
                        paymentProcessor.syncProduct(
                                plan.planId(), plan.displayName(), priceCents, MONTHLY_INTERVAL);

                // Sync annual price if applicable
                String annualPriceId = plan.stripeAnnualPriceId();
                Integer annualCents = plan.annualPriceCents();
                if (annualCents != null) {
                    try {
                        ProductPriceMapping annualMapping =
                                paymentProcessor.syncProduct(
                                        plan.planId() + "-annual",
                                        plan.displayName() + " (Annual)",
                                        annualCents,
                                        ANNUAL_INTERVAL);
                        annualPriceId = annualMapping.externalPriceId();
                    } catch (Exception e) {
                        LOG.warn(
                                "Failed to sync annual price for plan {} (non-fatal): {}",
                                plan.planId(),
                                e.getMessage());
                    }
                }

                coreDispatcher.dispatch(
                        new SyncPlanStripeIdsCommandActuator.SyncPlanStripeIdsCommand(
                                plan.planId(),
                                monthlyMapping.externalProductId(),
                                monthlyMapping.externalPriceId(),
                                annualPriceId));

                LOG.info(
                        "Synced plan {} → product={}, price={}, annualPrice={}",
                        plan.planId(),
                        monthlyMapping.externalProductId(),
                        monthlyMapping.externalPriceId(),
                        annualPriceId);
                synced++;
            } catch (Exception e) {
                // Non-fatal: plan will work without Stripe IDs, sync retries on next restart
                LOG.warn(
                        "Failed to sync plan {} to Stripe (non-fatal): {}",
                        plan.planId(),
                        e.getMessage());
            }
        }

        LOG.info("Stripe plan sync complete: {} synced, {} skipped", synced, skipped);
    }
}
