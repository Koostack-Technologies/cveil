package com.koostack.operio.core.models;

import java.time.Instant;

/**
 * Represents a user managed by an identity provider.
 *
 * <p>Returned by {@link com.koostack.operio.core.platform.iam.IdpProvider#registerUser}
 * and user lookup operations. Maps to the {@code platform_iam.platform_user}
 * table and/or the external IdP's user representation.
 *
 * @param userId   unique user identifier (Keycloak user ID, Auth0 sub, etc.)
 * @param email    the user's email address
 * @param fullName the user's display name, nullable
 * @param status   account status: {@code active}, {@code suspended}, or {@code unverified}
 * @param createdAt when the user was created
 */
public record IdpUser(
    String userId,
    String email,
    String fullName,
    String status,
    Instant createdAt
) {}
