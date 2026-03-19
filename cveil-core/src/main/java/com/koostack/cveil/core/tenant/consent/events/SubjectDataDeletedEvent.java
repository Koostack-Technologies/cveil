package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;
import com.koostack.cveil.core.event.Shreddable;

/**
 * Composite event emitted after a full DSAR data-deletion saga completes. Summarises the outcome of
 * the multi-step erasure (revoke all consent → purge cache → remove subject → log audit). This
 * event is consumed by outbound webhook delivery to notify external systems of the deletion.
 */
public record SubjectDataDeletedEvent(
        String subjectId,
        String deletionRequestId,
        long ledgerEntriesRevoked,
        long cachedKeysDeleted,
        String status,
        String actor,
        String ipAddress,
        String userAgent)
        implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() {
        return subjectId;
    }
}
