package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

public record ApiKeyRevokedEvent(
    String tenantId,
    String keyId
) implements DomainEvent {}
