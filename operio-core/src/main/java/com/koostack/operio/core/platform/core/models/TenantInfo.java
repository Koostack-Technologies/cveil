package com.koostack.operio.core.platform.core.models;

import java.time.Instant;

/**
 * Read-model DTO for tenant information.
 */
public record TenantInfo(
    String tenantId,
    String displayName,
    String plan,
    String status,
    String schemaPrefix,
    String redisAclUser,
    Instant createdAt,
    Instant updatedAt
) {}
