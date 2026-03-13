package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

public record UserDeletedEvent(String realmId, String keycloakUserId) implements DomainEvent {}
