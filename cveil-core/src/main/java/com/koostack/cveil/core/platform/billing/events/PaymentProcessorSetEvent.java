package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

public record PaymentProcessorSetEvent(String tenantId, String processor, String customerId)
        implements DomainEvent {}
