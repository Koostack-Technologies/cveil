package com.koostack.operio.core.platform.billing.events;

import com.koostack.operio.core.event.DomainEvent;

public record SubscriptionCreatedEvent(
        String tenantId, String planTier, int evalLimit, int subjectLimit, int burstLimit)
        implements DomainEvent {}
