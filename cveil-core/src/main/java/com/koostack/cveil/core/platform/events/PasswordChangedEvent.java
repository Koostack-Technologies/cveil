package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

/**
 * Domain event published when a user changes their password.
 *
 * @param userId the Keycloak user ID
 */
public record PasswordChangedEvent(String userId) implements DomainEvent {}
