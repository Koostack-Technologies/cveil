package com.koostack.operio.core.models;

import java.time.Instant;

public record DsarAuditView(
    long dsarId,
    String subjectId,
    String deletionRequestId,
    int policiesDeleted,
    int cachedKeysDeleted,
    int auditEntriesDeleted,
    String status,
    String actor,
    String ipAddress,
    String userAgent,
    String metadata,
    Instant createdAt
) {}
