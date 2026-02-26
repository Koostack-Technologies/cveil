package com.koostack.operio.core.control.events;

import com.koostack.operio.core.event.DomainEvent;

public record IdpConfigUpdatedEvent(
    String tenantId,
    String provider,
    String issuerUrl,
    String clientId
) implements DomainEvent {}
