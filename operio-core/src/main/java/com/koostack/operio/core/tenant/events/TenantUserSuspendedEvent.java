package com.koostack.operio.core.tenant.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantUserSuspendedEvent(String tenantId, String userId) implements DomainEvent {}
