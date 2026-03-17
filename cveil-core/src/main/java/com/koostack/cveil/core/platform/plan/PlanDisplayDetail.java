package com.koostack.cveil.core.platform.plan;

/**
 * Read-only view of the presentation-layer detail for a single plan.
 *
 * <p>Mirrors the {@code cveilcore.plan_display_detail} table and is used exclusively by the public
 * pricing page to render marketing copy, feature bullets, and CTA text.
 */
public record PlanDisplayDetail(
        String planId,
        String price,
        String period,
        String overage,
        String governance,
        String support,
        String residency,
        String cta,
        boolean highlighted) {}
