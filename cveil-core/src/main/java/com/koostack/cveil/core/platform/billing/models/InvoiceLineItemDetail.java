package com.koostack.cveil.core.platform.billing.models;

import java.time.Instant;

/** Processor-agnostic representation of a single line item within an invoice. */
public record InvoiceLineItemDetail(
        String externalLineItemId,
        String description,
        long amount,
        String currency,
        int quantity,
        long unitAmount,
        Instant periodStart,
        Instant periodEnd,
        boolean proration) {}
