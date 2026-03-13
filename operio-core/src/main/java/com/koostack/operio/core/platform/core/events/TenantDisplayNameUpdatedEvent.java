package com.koostack.operio.core.platform.core.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantDisplayNameUpdatedEvent(String tenantId, String displayName)
        implements DomainEvent {}
