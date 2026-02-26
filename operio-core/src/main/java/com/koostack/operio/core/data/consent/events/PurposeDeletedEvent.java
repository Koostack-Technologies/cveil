package com.koostack.operio.core.data.consent.events;

public record PurposeDeletedEvent(
    String purposeName
) implements DomainEvent {}
