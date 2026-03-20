package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a user's email address has been verified (synced from Keycloak). */
public record EmailVerifiedEvent(String realmId, String userId, String email)
        implements DomainEvent {}
