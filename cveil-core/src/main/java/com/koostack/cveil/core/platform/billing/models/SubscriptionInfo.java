package com.koostack.cveil.core.platform.billing.models;

import java.time.Instant;

/** Read-model DTO for subscription information. */
public record SubscriptionInfo(
        String tenantId,
        String planTier,
        int evalLimit,
        int subjectLimit,
        int burstLimit,
        String status,
        String externalSubscriptionId,
        String billingInterval,
        Instant periodStart,
        Instant periodEnd,
        boolean cancelAtPeriodEnd,
        Instant createdAt,
        Instant updatedAt) {

    /** Backwards-compatible constructor without billingInterval (defaults to "monthly"). */
    public SubscriptionInfo(
            String tenantId,
            String planTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit,
            String status,
            String externalSubscriptionId,
            Instant periodStart,
            Instant periodEnd,
            boolean cancelAtPeriodEnd,
            Instant createdAt,
            Instant updatedAt) {
        this(
                tenantId,
                planTier,
                evalLimit,
                subjectLimit,
                burstLimit,
                status,
                externalSubscriptionId,
                "monthly",
                periodStart,
                periodEnd,
                cancelAtPeriodEnd,
                createdAt,
                updatedAt);
    }
}
