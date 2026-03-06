package com.koostack.operio.core.models;

import java.time.Instant;

/**
 * Immutable snapshot of a tenant's metadata.
 *
 * <p>Returned by platform query services. Rate-limit and quota fields are now managed at the
 * subscription level via {@code BillingQueryService}.
 *
 * @param tenantId unique tenant identifier (e.g. {@code "acme_corp"})
 * @param displayName human-readable tenant name
 * @param plan subscription plan ({@code "free"}, {@code "pro"}, {@code "enterprise"})
 * @param status tenant status ({@code "ACTIVE"}, {@code "SUSPENDED"}, {@code "DELETED"})
 * @param createdAt when the tenant was provisioned
 */
public record TenantInfo(
        String tenantId, String displayName, String plan, String status, Instant createdAt) {}
