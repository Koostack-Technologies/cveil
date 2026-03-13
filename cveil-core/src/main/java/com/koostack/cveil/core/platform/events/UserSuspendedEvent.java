package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

public record UserSuspendedEvent(String realmId, String userId) implements DomainEvent {}
