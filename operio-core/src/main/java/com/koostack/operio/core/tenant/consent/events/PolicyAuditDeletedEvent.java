package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record PolicyAuditDeletedEvent(
    String subjectId
) implements DomainEvent {}
