package com.koostack.cveil.core.tenant.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantUserSuspendedEvent(String tenantId, String userId) implements DomainEvent {}
