package com.koostack.operio.core.platform.billing.models;

import java.time.Instant;

/**
 * Read-model DTO for subscription information.
 */
public record SubscriptionInfo(
    String tenantId,
    String planTier,
    int evalLimit,
    int subjectLimit,
    int burstLimit,
    String status,
    Instant periodStart,
    Instant periodEnd,
    boolean cancelAtPeriodEnd,
    Instant createdAt,
    Instant updatedAt
) {}
