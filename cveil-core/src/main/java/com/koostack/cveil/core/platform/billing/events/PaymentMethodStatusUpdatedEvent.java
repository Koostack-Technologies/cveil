package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

public record PaymentMethodStatusUpdatedEvent(String tenantId, String status)
        implements DomainEvent {}
