package com.koostack.cveil.core.platform.core.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantPlanChangedEvent(String tenantId, String oldPlan, String newPlan)
        implements DomainEvent {}
