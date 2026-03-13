package com.koostack.operio.core.platform.billing.events;

import com.koostack.operio.core.event.DomainEvent;

public record PaymentProcessorSetEvent(String tenantId, String processor, String customerId)
        implements DomainEvent {}
