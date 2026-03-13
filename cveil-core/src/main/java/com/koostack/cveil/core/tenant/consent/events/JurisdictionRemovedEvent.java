package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record JurisdictionRemovedEvent(String jurisdictionName) implements DomainEvent {}
