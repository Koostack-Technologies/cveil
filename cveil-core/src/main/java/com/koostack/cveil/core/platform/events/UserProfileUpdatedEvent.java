package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

/**
 * Domain event published when a user updates their profile (display name, email).
 *
 * @param userId the Keycloak user ID
 * @param fullName updated full name (null if unchanged)
 * @param email updated email (null if unchanged)
 */
public record UserProfileUpdatedEvent(String userId, String fullName, String email)
        implements DomainEvent {}
