package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

/** Published when a platform user's session is terminated (refresh token revoked). */
public record UserLoggedOutEvent(String realmId, String userId) implements DomainEvent {}
