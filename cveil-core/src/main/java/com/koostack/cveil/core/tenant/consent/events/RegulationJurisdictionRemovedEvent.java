package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record RegulationJurisdictionRemovedEvent(
        String regulationName, String jurisdictionName, RegulationSnapshot snapshot)
        implements DomainEvent {}
