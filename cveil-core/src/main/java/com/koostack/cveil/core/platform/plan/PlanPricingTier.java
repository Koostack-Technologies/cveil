package com.koostack.cveil.core.platform.plan;

import com.koostack.cveil.core.platform.plan.PlanQueryService.PlanInfo;
import java.util.Locale;

/**
 * Combined pricing tier view that joins operational plan data with presentation detail.
 *
 * <p>Designed to be passed directly into the Qute pricing template — every field name matches the
 * template variable expected by {@code plans.html}.
 */
public record PlanPricingTier(
        String planId,
        String name,
        String description,
        String price,
        String period,
        String subjects,
        String apiRequests,
        String overage,
        String governance,
        String support,
        String residency,
        String cta,
        boolean highlighted) {

    /**
     * Constructs a pricing tier from a plan definition and its display detail.
     *
     * @param plan the operational plan info
     * @param detail the presentation-layer display detail
     */
    public static PlanPricingTier of(PlanInfo plan, PlanDisplayDetail detail) {
        return new PlanPricingTier(
                plan.planId(),
                plan.displayName(),
                plan.description(),
                detail.price(),
                detail.period(),
                formatLimit(plan.subjectLimit()),
                formatLimit(plan.evalLimit()),
                detail.overage(),
                detail.governance(),
                detail.support(),
                detail.residency(),
                detail.cta(),
                detail.highlighted());
    }

    private static String formatLimit(long limit) {
        if (limit < 0) return "Custom";
        return String.format(Locale.US, "%,d", limit);
    }
}
