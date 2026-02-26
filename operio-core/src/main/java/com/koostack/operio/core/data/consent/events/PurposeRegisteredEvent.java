package com.koostack.operio.core.data.consent.events;

public record PurposeRegisteredEvent(
    String purposeName,
    String description
) implements DomainEvent {}
