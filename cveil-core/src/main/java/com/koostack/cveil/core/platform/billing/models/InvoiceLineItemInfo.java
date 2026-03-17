package com.koostack.cveil.core.platform.billing.models;

import java.time.Instant;

/** Read-model DTO for an invoice line item. */
public record InvoiceLineItemInfo(
        String id,
        String invoiceId,
        String externalLineItemId,
        String description,
        long amount,
        String currency,
        int quantity,
        long unitAmount,
        Instant periodStart,
        Instant periodEnd,
        boolean proration,
        Instant createdAt) {}
