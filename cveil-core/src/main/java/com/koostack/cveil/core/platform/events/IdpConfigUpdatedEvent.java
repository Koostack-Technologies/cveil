package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

public record IdpConfigUpdatedEvent(
        String tenantId, String provider, String issuerUrl, String clientId)
        implements DomainEvent {}
