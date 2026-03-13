package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

/**
 * Domain event published when a user updates app-specific profile fields that live only in the
 * local IAM mirror — not in Keycloak.
 *
 * @param userId the Keycloak user ID (JWT sub claim)
 * @param displayName app display name override (null if unchanged)
 * @param avatarUrl avatar URL (null if unchanged)
 * @param locale preferred locale, e.g. "en-US" (null if unchanged)
 * @param timezone preferred timezone, e.g. "America/New_York" (null if unchanged)
 */
public record UserAppProfileUpdatedEvent(
        String userId, String displayName, String avatarUrl, String locale, String timezone)
        implements DomainEvent {}
