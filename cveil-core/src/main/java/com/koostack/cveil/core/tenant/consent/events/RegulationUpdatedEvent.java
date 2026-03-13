package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record RegulationUpdatedEvent(
        String regulationName, String description, RegulationSnapshot snapshot)
        implements DomainEvent {}
