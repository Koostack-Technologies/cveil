package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

/**
 * Domain event published when a user changes their password.
 *
 * @param userId the Keycloak user ID
 */
public record PasswordChangedEvent(String userId) implements DomainEvent {}
