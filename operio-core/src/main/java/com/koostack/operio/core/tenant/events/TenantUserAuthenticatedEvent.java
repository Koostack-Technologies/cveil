package com.koostack.operio.core.tenant.events;

import com.koostack.operio.core.event.DomainEvent;

/** Published when a tenant user successfully authenticates via ROPC grant. */
public record TenantUserAuthenticatedEvent(String tenantId, String userId, String email)
        implements DomainEvent {}
