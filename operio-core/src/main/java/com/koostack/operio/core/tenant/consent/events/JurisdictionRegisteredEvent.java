package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record JurisdictionRegisteredEvent(String jurisdictionName, String description)
        implements DomainEvent {}
