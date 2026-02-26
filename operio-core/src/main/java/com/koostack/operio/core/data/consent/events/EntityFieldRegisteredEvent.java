package com.koostack.operio.core.data.consent.events;

public record EntityFieldRegisteredEvent(
    String entityName,
    String fieldName,
    String description
) implements DomainEvent {}
