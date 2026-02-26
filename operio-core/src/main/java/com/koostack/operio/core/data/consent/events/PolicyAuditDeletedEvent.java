package com.koostack.operio.core.data.consent.events;

public record PolicyAuditDeletedEvent(
    String subjectId
) implements DomainEvent {}
