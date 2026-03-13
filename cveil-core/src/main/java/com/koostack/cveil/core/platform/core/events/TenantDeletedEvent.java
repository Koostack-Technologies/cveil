package com.koostack.cveil.core.platform.core.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantDeletedEvent(String tenantId) implements DomainEvent {}
