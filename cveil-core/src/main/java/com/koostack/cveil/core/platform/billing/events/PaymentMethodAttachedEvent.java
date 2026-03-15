package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a payment method is attached to a tenant's billing profile. */
public record PaymentMethodAttachedEvent(
        String tenantId,
        String externalPaymentMethodId,
        String type,
        String brand,
        String last4,
        int expMonth,
        int expYear,
        boolean isDefault)
        implements DomainEvent {}
