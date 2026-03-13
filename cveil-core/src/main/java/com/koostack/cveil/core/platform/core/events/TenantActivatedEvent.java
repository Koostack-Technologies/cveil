package com.koostack.cveil.core.platform.core.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantActivatedEvent(String tenantId) implements DomainEvent {}
