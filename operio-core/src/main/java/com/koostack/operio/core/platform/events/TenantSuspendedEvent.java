package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantSuspendedEvent(
    String tenantId,
    String reason
) implements DomainEvent {}
