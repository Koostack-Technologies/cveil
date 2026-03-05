package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record SubjectRegisteredEvent(
        String subjectName,
        String dsarKey,
        String description
) implements DomainEvent {}
