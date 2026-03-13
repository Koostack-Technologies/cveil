package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;
import java.time.Instant;

/**
 * Domain event published when a user creates a personal access token (PAT).
 *
 * @param tenantId the tenant the token is scoped to
 * @param tokenPrefix the visible prefix for identification (e.g. {@code cveil_a8Kx3mNp})
 * @param name human-readable name chosen by the user
 * @param roles comma-separated Keycloak roles snapshotted at creation
 * @param createdBy the Keycloak user ID (subject) of the creator
 * @param expiresAt when the token expires
 */
public record AccessTokenCreatedEvent(
        String tenantId,
        String tokenPrefix,
        String name,
        String roles,
        String createdBy,
        Instant expiresAt)
        implements DomainEvent {}
