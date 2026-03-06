package com.koostack.operio.core.platform.billing.events;

import com.koostack.operio.core.event.DomainEvent;

public record PaymentMethodStatusUpdatedEvent(String tenantId, String status)
        implements DomainEvent {}
