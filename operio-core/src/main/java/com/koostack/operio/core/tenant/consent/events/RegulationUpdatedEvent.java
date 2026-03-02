package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record RegulationUpdatedEvent(
        String regulationName,
        String description
) implements DomainEvent {}
