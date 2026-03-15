package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;
import java.time.Instant;

/** Published when an invoice is successfully paid. */
public record InvoicePaidEvent(
        String tenantId, String externalInvoiceId, long amountPaid, Instant paidAt)
        implements DomainEvent {}
