package com.koostack.operio.core.platform.core.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantPlanChangedEvent(String tenantId, String oldPlan, String newPlan)
        implements DomainEvent {}
