package com.koostack.operio.core.data.consent.events;

public record EntityFieldRemovedEvent(
    String entityName,
    String fieldName
) implements DomainEvent {}
