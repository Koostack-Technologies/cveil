package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

/** Published when a platform user's access token is refreshed. */
public record TokenRefreshedEvent(String realmId, String userId) implements DomainEvent {}

