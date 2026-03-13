package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record RegulationJurisdictionAddedEvent(
        String regulationName,
        String jurisdictionName,
        String description,
        RegulationSnapshot snapshot)
        implements DomainEvent {}
