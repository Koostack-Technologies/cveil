package com.koostack.operio.core.control.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantDeletedEvent(
    String tenantId
) implements DomainEvent {}
