package com.koostack.cveil.core.platform.billing.models;

import java.time.Instant;
import java.util.List;

/**
 * Processor-agnostic representation of a subscription returned from an external payment processor.
 */
public record SubscriptionDetail(
        String externalSubscriptionId,
        String externalCustomerId,
        String status,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        List<SubscriptionItemDetail> items) {

    /** A single line item within a subscription (e.g. base plan price, metered usage component). */
    public record SubscriptionItemDetail(
            String externalSubscriptionItemId, String externalPriceId, long quantity) {}
}
