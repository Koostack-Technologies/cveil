package com.koostack.operio.core.tenant.events;

import com.koostack.operio.core.event.DomainEvent;
import java.util.Set;

/** Published when a user is registered in a tenant's Keycloak realm. */
public record TenantUserRegisteredEvent(
        String tenantId,
        String userId,
        String username,
        String email,
        String fullName,
        Set<String> roles,
        boolean temporaryPassword)
        implements DomainEvent {}
