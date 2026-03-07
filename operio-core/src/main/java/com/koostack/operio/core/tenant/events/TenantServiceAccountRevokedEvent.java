package com.koostack.operio.core.tenant.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantServiceAccountRevokedEvent(String tenantId, String clientId)
        implements DomainEvent {}
