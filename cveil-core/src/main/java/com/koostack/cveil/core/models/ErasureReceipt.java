package com.koostack.cveil.core.models;

import java.time.Instant;

/**
 * Domain model representing the result of a subject erasure operation (GDPR Article 17 — right to
 * be forgotten).
 *
 * <p>Returned by {@link com.koostack.cveil.core.TenantService#eraseSubject} after the orchestrated
 * erasure sequence completes. The {@code status} will be {@code "PENDING_SHRED"} when all steps
 * succeed (the encryption key is not deleted inline — callers must invoke the shred endpoint
 * separately after confirming that the outbox has flushed), or {@code "PARTIAL_FAILURE"} if one or
 * more steps failed.
 *
 * @param subjectId the data subject identifier
 * @param requestId unique DSAR request identifier
 * @param ledgerEntriesRevoked number of consent ledger entries revoked
 * @param cachedKeysDeleted number of cached consent-decision keys deleted
 * @param status {@code "PENDING_SHRED"} or {@code "PARTIAL_FAILURE"}
 * @param completedAt timestamp when the erasure completed
 */
public record ErasureReceipt(
        String subjectId,
        String requestId,
        long ledgerEntriesRevoked,
        long cachedKeysDeleted,
        String status,
        Instant completedAt) {}
