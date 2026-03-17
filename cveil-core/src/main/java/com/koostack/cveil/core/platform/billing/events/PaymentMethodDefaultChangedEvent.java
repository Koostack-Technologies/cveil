package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a tenant's default payment method changes. */
public record PaymentMethodDefaultChangedEvent(String tenantId, String externalPaymentMethodId)
        implements DomainEvent {}
