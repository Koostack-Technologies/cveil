package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

public record BillingProfileCreatedEvent(String tenantId, String billingEmail)
        implements DomainEvent {}
