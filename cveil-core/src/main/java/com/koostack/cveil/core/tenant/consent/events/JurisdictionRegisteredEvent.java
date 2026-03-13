package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record JurisdictionRegisteredEvent(String jurisdictionName, String description)
        implements DomainEvent {}
