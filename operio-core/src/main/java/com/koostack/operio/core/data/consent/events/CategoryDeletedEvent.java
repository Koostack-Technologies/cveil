package com.koostack.operio.core.data.consent.events;

public record CategoryDeletedEvent(
    String categoryName
) implements DomainEvent {}
