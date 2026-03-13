package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;
import com.koostack.cveil.core.event.Shreddable;

public record SubjectAuditDeletedEvent(
        String subjectId,
        String deletionRequestId,
        String actor,
        String ipAddress,
        String userAgent)
        implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() {
        return subjectId;
    }
}
