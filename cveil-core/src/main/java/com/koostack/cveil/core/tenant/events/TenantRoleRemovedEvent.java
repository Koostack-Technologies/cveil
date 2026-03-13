package com.koostack.cveil.core.tenant.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantRoleRemovedEvent(String tenantId, String userId, String role)
        implements DomainEvent {}
