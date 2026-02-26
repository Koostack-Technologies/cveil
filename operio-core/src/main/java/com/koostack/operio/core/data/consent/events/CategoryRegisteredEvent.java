package com.koostack.operio.core.data.consent.events;

public record CategoryRegisteredEvent(
    String categoryName,
    String description
) implements DomainEvent {}
