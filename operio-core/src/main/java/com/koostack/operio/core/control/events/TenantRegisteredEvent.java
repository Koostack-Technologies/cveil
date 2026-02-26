package com.koostack.operio.core.control.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantRegisteredEvent(
    String tenantId,
    String tenantName,
    String plan
) implements DomainEvent {}
