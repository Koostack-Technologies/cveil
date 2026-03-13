package com.koostack.cveil.core.tenant.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantUserActivatedEvent(String tenantId, String userId) implements DomainEvent {}
