package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

public record RoleAssignedEvent(String realmId, String userId, String role)
        implements DomainEvent {}
