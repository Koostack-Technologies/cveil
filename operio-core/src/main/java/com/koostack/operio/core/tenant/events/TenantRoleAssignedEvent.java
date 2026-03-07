package com.koostack.operio.core.tenant.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantRoleAssignedEvent(String tenantId, String userId, String role)
        implements DomainEvent {}
