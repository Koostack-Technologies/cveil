package com.koostack.cveil.core.platform.plan;

import com.koostack.cveil.core.spi.Plugin;
import java.util.List;

/**
 * Query service for subscription plans — reads pre-seeded plan definitions from the {@code
 * cveilcore.plan} table.
 *
 * <p>Used by the platform tenant-creation flow to validate that the requested plan exists and is
 * active, and to retrieve limits for subscription creation.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface PlanQueryService<T> extends Plugin<T> {

    /** Read-only view of a plan definition. */
    record PlanInfo(
            String planId,
            String displayName,
            String description,
            long evalLimit,
            long subjectLimit,
            int burstLimit,
            int jurisdictionLimit,
            int memberLimit,
            int auditRetentionDays,
            boolean isPublic,
            boolean isActive,
            int sortOrder,
            String stripeProductId,
            String stripePriceId,
            String stripeAnnualPriceId,
            Integer monthlyPriceCents,
            Integer annualPriceCents) {

        /** Backwards-compatible constructor without pricing columns. */
        public PlanInfo(
                String planId,
                String displayName,
                String description,
                long evalLimit,
                long subjectLimit,
                int burstLimit,
                int jurisdictionLimit,
                int memberLimit,
                int auditRetentionDays,
                boolean isPublic,
                boolean isActive,
                int sortOrder,
                String stripeProductId,
                String stripePriceId,
                String stripeAnnualPriceId) {
            this(
                    planId,
                    displayName,
                    description,
                    evalLimit,
                    subjectLimit,
                    burstLimit,
                    jurisdictionLimit,
                    memberLimit,
                    auditRetentionDays,
                    isPublic,
                    isActive,
                    sortOrder,
                    stripeProductId,
                    stripePriceId,
                    stripeAnnualPriceId,
                    null,
                    null);
        }

        /** Returns true if this plan has a billable price (non-null, non-zero monthly). */
        public boolean isBillable() {
            return monthlyPriceCents != null && monthlyPriceCents > 0;
        }

        /** Returns true if this plan has an annual pricing option. */
        public boolean hasAnnualPricing() {
            return annualPriceCents != null && annualPriceCents > 0;
        }

        /** Returns true if jurisdictions are unlimited on this plan (-1). */
        public boolean isJurisdictionUnlimited() {
            return jurisdictionLimit < 0;
        }

        /** Returns true if team members are unlimited on this plan (-1). */
        public boolean isMemberUnlimited() {
            return memberLimit < 0;
        }

        /** Returns true if audit retention is unlimited on this plan (-1). */
        public boolean isAuditRetentionUnlimited() {
            return auditRetentionDays < 0;
        }
    }

    /**
     * Get a single plan by ID.
     *
     * @param planId the plan identifier
     * @return the plan info, or {@code null} if not found
     */
    PlanInfo getPlan(String planId);

    /**
     * List all plans.
     *
     * @return all plan definitions
     */
    List<PlanInfo> listPlans();

    /**
     * List only active plans.
     *
     * @return active plan definitions, sorted by {@code sort_order}
     */
    List<PlanInfo> listActivePlans();

    /**
     * List active, public plans joined with their display detail for the pricing page.
     *
     * @return pricing tiers sorted by {@code sort_order}
     */
    default List<PlanPricingTier> listPricingTiers() {
        throw new UnsupportedOperationException("listPricingTiers not implemented");
    }
}
