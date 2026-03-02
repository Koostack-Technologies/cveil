package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record SubjectAuditDeletedEvent(
        String subjectId,
        String deletionRequestId,
        String actor,
        String ipAddress,
        String userAgent
) implements DomainEvent {}
