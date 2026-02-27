package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

public record BillingPlanChangedEvent(
    String tenantId,
    String previousPlan,
    String newPlan
) implements DomainEvent {}
