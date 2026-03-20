package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a verification email is sent (or re-sent) to a user. */
public record EmailVerificationSentEvent(String realmId, String userId, String email)
        implements DomainEvent {}
