package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record SubjectUpdatedEvent(
        String subjectName,
        String description
) implements DomainEvent {}
