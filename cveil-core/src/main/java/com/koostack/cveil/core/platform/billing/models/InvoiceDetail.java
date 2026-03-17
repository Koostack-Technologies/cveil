package com.koostack.cveil.core.platform.billing.models;

import java.time.Instant;
import java.util.List;

/** Processor-agnostic representation of an invoice returned from an external payment processor. */
public record InvoiceDetail(
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
        List<InvoiceLineItemDetail> lineItems) {}
