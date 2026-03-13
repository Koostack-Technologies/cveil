package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record JurisdictionUpdatedEvent(String jurisdictionName, String description)
        implements DomainEvent {}
