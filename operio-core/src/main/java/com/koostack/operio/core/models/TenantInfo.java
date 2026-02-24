package com.koostack.operio.core.models;

import java.time.Instant;
/**
 * Immutable snapshot of a tenant's metadata.
 *
 * <p>Returned by {@link com.koostack.operio.core.service.PlatformAdminService}
 * query methods. Contains plan-level limits used for rate limiting and
 * quota enforcement at the REST layer.
 *
 * @param tenantId          unique tenant identifier (e.g. {@code "acme_corp"})
 * @param displayName       human-readable tenant name
 * @param plan              subscription plan ({@code "free"}, {@code "pro"},
 *                          {@code "enterprise"})
 * @param status            tenant status ({@code "ACTIVE"}, {@code "SUSPENDED"},
 *                          {@code "DELETED"})
 * @param evalLimitMonthly  maximum consent evaluations per calendar month
 * @param subjectLimit      maximum distinct data subjects
 * @param burstLimitPerMin  maximum requests per minute (burst cap)
 * @param createdAt         when the tenant was provisioned
 */
public record TenantInfo(
    String tenantId,
    String displayName,
    String plan,
    String status,
    long evalLimitMonthly,
    long subjectLimit,
    int burstLimitPerMin,
    Instant createdAt
) {}
