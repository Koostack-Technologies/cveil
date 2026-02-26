package com.koostack.operio.core.data.consent.events;

public record RegulationDeletedEvent(
    String regulationName
) implements DomainEvent {}
