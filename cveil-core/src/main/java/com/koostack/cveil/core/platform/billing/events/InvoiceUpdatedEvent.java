package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;
import java.time.Instant;

/** Published when an invoice is updated (status change, payment attempt, etc.). */
public record InvoiceUpdatedEvent(
        String tenantId,
        String externalInvoiceId,
        String status,
        long amountDue,
        long amountPaid,
        long amountRemaining,
        long total,
        String hostedInvoiceUrl,
        String invoicePdfUrl,
        Instant paidAt)
        implements DomainEvent {}
