package com.koostack.operio.core.data.consent.events;

public record RegulationRegisteredEvent(
    String regulationName,
    String fullName,
    String jurisdiction,
    String description,
    String url
) implements DomainEvent {}
