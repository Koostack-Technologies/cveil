package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

public record SubscriptionCreatedEvent(
        String tenantId, String planTier, int evalLimit, int subjectLimit, int burstLimit)
        implements DomainEvent {}
