package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

public record RoleAssignedEvent(String realmId, String userId, String role)
        implements DomainEvent {}
