package com.koostack.operio.core.platform.core.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantCreatedEvent(
        String tenantId, String displayName, String plan, String tenantType)
        implements DomainEvent {}
