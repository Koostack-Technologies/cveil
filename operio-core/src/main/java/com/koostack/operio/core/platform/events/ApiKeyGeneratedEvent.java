package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

import java.time.Instant;

public record ApiKeyGeneratedEvent(
    String tenantId,
    String keyId,
    String keyPrefix,
    Instant expiresAt
) implements DomainEvent {}
