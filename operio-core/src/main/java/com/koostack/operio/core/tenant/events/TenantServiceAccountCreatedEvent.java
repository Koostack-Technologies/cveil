package com.koostack.operio.core.tenant.events;

import com.koostack.operio.core.event.DomainEvent;
import java.util.Set;

public record TenantServiceAccountCreatedEvent(
        String tenantId, String clientId, String name, Set<String> roles)
        implements DomainEvent {}
