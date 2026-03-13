package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a platform user successfully authenticates via ROPC grant. */
public record UserAuthenticatedEvent(String realmId, String userId, String email)
        implements DomainEvent {}
