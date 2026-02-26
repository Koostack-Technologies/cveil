package com.koostack.operio.core.data.consent.events;

public record EntityDeletedEvent(
    String entityName
) implements DomainEvent {}
