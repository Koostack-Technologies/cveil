package com.koostack.operio.core.platform.billing.events;

import com.koostack.operio.core.event.DomainEvent;

public record SubscriptionPlanChangedEvent(
        String tenantId,
        String oldPlanTier,
        String newPlanTier,
        int evalLimit,
        int subjectLimit,
        int burstLimit)
        implements DomainEvent {}
