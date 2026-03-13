package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Published when a platform user's access token is refreshed. */
public record TokenRefreshedEvent(String realmId, String userId) implements DomainEvent {}
