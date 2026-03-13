package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record RegulationJurisdictionAddedEvent(
        String regulationName,
        String jurisdictionName,
        String description,
        RegulationSnapshot snapshot)
        implements DomainEvent {}
