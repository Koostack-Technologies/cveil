package com.koostack.cveil.core.platform.core.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantDisplayNameUpdatedEvent(String tenantId, String displayName)
        implements DomainEvent {}
