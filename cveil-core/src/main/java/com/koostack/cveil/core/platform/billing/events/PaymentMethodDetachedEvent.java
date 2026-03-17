package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a payment method is detached from a tenant's billing profile. */
public record PaymentMethodDetachedEvent(String tenantId, String externalPaymentMethodId)
        implements DomainEvent {}
