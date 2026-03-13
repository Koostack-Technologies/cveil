package com.koostack.cveil.core.tenant.events;

import com.koostack.cveil.core.event.DomainEvent;

public record TenantRoleAssignedEvent(String tenantId, String userId, String role)
        implements DomainEvent {}
