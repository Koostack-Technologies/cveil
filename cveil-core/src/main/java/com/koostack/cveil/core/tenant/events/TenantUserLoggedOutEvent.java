package com.koostack.cveil.core.tenant.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a tenant user's session is terminated (refresh token revoked). */
public record TenantUserLoggedOutEvent(String tenantId, String userId) implements DomainEvent {}
