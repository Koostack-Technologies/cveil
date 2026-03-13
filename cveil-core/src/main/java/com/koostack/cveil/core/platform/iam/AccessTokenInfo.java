package com.koostack.cveil.core.platform.iam;

import java.time.Instant;

/**
 * Immutable snapshot of an access token's metadata (never includes the raw token or hash).
 *
 * @param id database primary key
 * @param tokenPrefix visible prefix for identification (e.g. {@code cveil_a8Kx3mNp})
 * @param name human-readable name chosen by the user
 * @param tenantId the tenant this token is scoped to
 * @param roles comma-separated Keycloak roles snapshotted at creation
 * @param status token status ({@code "active"}, {@code "revoked"}, {@code "expired"})
 * @param expiresAt when the token expires
 * @param createdBy the Keycloak user ID (subject) of the creator
 * @param lastUsedAt last time the token was used for authentication
 * @param createdAt when the token was created
 */
public record AccessTokenInfo(
        long id,
        String tokenPrefix,
        String name,
        String tenantId,
        String roles,
        String status,
        Instant expiresAt,
        String createdBy,
        Instant lastUsedAt,
        Instant createdAt) {}
