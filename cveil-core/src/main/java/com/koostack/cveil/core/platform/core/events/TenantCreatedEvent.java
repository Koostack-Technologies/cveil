package com.koostack.cveil.core.platform.core.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantCreatedEvent(
        String tenantId, String displayName, String plan, String tenantType)
        implements DomainEvent {}
