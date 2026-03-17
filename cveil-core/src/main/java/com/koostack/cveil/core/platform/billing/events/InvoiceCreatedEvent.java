package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;
import java.time.Instant;

/** Published when an invoice is created (typically from a webhook). */
public record InvoiceCreatedEvent(
        String tenantId,
        String externalInvoiceId,
        String externalSubscriptionId,
        String status,
        String currency,
        long amountDue,
        long subtotal,
        long total,
        String hostedInvoiceUrl,
        String invoicePdfUrl,
        Instant periodStart,
        Instant periodEnd,
        Instant dueDate)
        implements DomainEvent {}
