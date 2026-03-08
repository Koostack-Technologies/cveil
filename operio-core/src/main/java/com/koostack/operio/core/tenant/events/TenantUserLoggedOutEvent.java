package com.koostack.operio.core.tenant.events;

import com.koostack.operio.core.event.DomainEvent;

/** Published when a tenant user's session is terminated (refresh token revoked). */
public record TenantUserLoggedOutEvent(String tenantId, String userId) implements DomainEvent {}

