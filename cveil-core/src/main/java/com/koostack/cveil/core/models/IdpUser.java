package com.koostack.cveil.core.models;

import java.time.Instant;

/**
 * Represents a user managed by an identity provider.
 *
 * <p>Returned by user lookup operations on {@link
 * com.koostack.cveil.core.platform.iam.IamQueryService} and {@link
 * com.koostack.cveil.core.tenant.iam.TenantIamQueryService}.
 *
 * @param username the user's unique login name
 * @param email the user's email address
 * @param fullName the user's display name, nullable
 * @param status account status: {@code active}, {@code suspended}, or {@code unverified}
 * @param createdAt when the user was created
 */
public record IdpUser(
        String username, String email, String fullName, String status, Instant createdAt) {}
