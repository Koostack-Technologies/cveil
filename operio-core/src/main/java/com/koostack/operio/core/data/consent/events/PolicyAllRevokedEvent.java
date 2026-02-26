package com.koostack.operio.core.data.consent.events;

public record PolicyAllRevokedEvent(
    String subjectId
) implements DomainEvent {}
