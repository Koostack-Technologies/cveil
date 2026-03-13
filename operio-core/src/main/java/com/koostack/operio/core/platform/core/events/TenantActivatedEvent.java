package com.koostack.operio.core.platform.core.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantActivatedEvent(String tenantId) implements DomainEvent {}
