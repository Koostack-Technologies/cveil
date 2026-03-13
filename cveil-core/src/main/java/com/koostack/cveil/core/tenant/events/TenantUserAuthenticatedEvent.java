package com.koostack.cveil.core.tenant.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a tenant user successfully authenticates via ROPC grant. */
public record TenantUserAuthenticatedEvent(String tenantId, String userId, String email)
        implements DomainEvent {}
