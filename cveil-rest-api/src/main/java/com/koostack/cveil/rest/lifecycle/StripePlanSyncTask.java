package com.koostack.cveil.rest.lifecycle;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.platform.billing.PaymentProcessorService;
import com.koostack.cveil.core.platform.billing.models.ProductPriceMapping;
import com.koostack.cveil.core.platform.plan.PlanQueryService;
import com.koostack.cveil.core.platform.plan.PlanQueryService.PlanInfo;
import com.koostack.cveil.platform.core.jooq.SyncPlanStripeIdsCommandActuator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotent startup task that ensures every billable plan has a corresponding Stripe Product +
 * Price.
 *
 * <p>On each application boot this task:
 *
 * <ol>
 *   <li>Reads all active plans from {@code platform_core.plan}
 *   <li>Skips plans that already have Stripe IDs persisted (fast path)
 *   <li>For plans that need syncing, calls {@link PaymentProcessorService#syncProduct} which is
 *       itself idempotent (searches Stripe by {@code metadata.plan_id} before creating)
 *   <li>Dispatches a {@code SyncPlanStripeIdsCommand} so the event store captures the mapping and
 *       the core projection materializes the Stripe IDs onto the plan read model
 * </ol>
 *
 * <p>Enterprise plans (custom pricing) are skipped entirely — they have no fixed price to sync.
 */
public final class StripePlanSyncTask {

    private static final Logger LOG = LoggerFactory.getLogger(StripePlanSyncTask.class);

    /**
     * Price in cents for each plan. Plans not listed here are skipped. Enterprise is excluded
     * because it uses custom/negotiated pricing.
     */
    private static final Map<String, Integer> PLAN_PRICE_CENTS =
            Map.of(
                    "free", 0,
                    "pro", 4900,
                    "business", 19900);

    private static final String BILLING_INTERVAL = "month";

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
            Integer priceCents = PLAN_PRICE_CENTS.get(plan.planId());
            if (priceCents == null) {
                LOG.debug("Skipping plan {} — no fixed price (custom/enterprise)", plan.planId());
                skipped++;
                continue;
            }

            // Fast path: already synced — both Stripe IDs present
            if (plan.stripeProductId() != null
                    && !plan.stripeProductId().isBlank()
                    && plan.stripePriceId() != null
                    && !plan.stripePriceId().isBlank()) {
                LOG.debug(
                        "Plan {} already synced (product={}, price={})",
                        plan.planId(),
                        plan.stripeProductId(),
                        plan.stripePriceId());
                skipped++;
                continue;
            }

            try {
                ProductPriceMapping mapping =
                        paymentProcessor.syncProduct(
                                plan.planId(), plan.displayName(), priceCents, BILLING_INTERVAL);

                coreDispatcher.dispatch(
                        new SyncPlanStripeIdsCommandActuator.SyncPlanStripeIdsCommand(
                                plan.planId(),
                                mapping.externalProductId(),
                                mapping.externalPriceId()));

                LOG.info(
                        "Synced plan {} → product={}, price={}",
                        plan.planId(),
                        mapping.externalProductId(),
                        mapping.externalPriceId());
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
