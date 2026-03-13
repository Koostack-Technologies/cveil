package com.koostack.cveil.core.tenant.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a tenant user's access token is refreshed. */
public record TenantTokenRefreshedEvent(String tenantId, String userId) implements DomainEvent {}
