package com.koostack.cveil.core.platform.core.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantSuspendedEvent(String tenantId, String reason) implements DomainEvent {}
