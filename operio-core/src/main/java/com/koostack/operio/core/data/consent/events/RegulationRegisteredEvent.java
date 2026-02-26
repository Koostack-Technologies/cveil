package com.koostack.operio.core.data.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record RegulationRegisteredEvent(
    String regulationName,
    String fullName,
    String jurisdiction,
    String description,
    String url
) implements DomainEvent {}
