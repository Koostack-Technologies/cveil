package com.koostack.cveil.core.platform.billing.models;

import java.time.Instant;
import java.util.List;

/** Read-model DTO for an invoice mirror record. */
public record InvoiceInfo(
        String id,
        String tenantId,
        String externalInvoiceId,
        String externalSubscriptionId,
        String status,
        String currency,
        long amountDue,
        long amountPaid,
        long amountRemaining,
        long subtotal,
        long tax,
        long total,
        String hostedInvoiceUrl,
        String invoicePdfUrl,
        Instant periodStart,
        Instant periodEnd,
        Instant dueDate,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt,
        List<InvoiceLineItemInfo> lineItems) {}
